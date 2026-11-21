import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.github.jk1.dependency-license-report")
}

/** Gradle flags are conventionally passed as -Pname (empty value) or -Pname=true. */
fun flag(name: String): Boolean {
    val value = providers.gradleProperty(name).orNull ?: return false
    return value.isEmpty() || value.toBoolean()
}

licenseReport {
    configurations = arrayOf("releaseRuntimeClasspath")
    outputDir = layout.buildDirectory.dir("reports/licenses").get().asFile.absolutePath
    renderers = arrayOf(com.github.jk1.license.render.JsonReportRenderer("licenses.json"))
}

val generateLicenses = tasks.register<Exec>("generateLicenses") {
    group = "reporting"
    description = "Regenerates app/src/main/assets/licenses.json from Gradle, Cargo and native license metadata."
    dependsOn("generateLicenseReport")
    workingDir = rootProject.projectDir
    commandLine("python3", "scripts/generate-licenses.py")
}

// A dependency change must not leave the manually generated report stale.
tasks.named("generateLicenseReport") { outputs.upToDateWhen { false } }

// ── ABI packaging ────────────────────────────────────────────────────
// Debug is a fast single-ABI build by default; release is universal and is
// what F-Droid and GitHub releases use. A specific ABI can always be forced
// with -Pabi=..., and -Pabi=universal (or all) makes debug universal too.
//
//   ./gradlew assembleDebug                         arm64-v8a only
//   ./gradlew assembleDebug -Pabi=x86_64           one ABI (emulator)
//   ./gradlew assembleDebug -Pabi=universal        all ABIs
//   ./gradlew assembleRelease                      all ABIs + universal
//
// `android.injected.build.abi` is the property an IDE sets when it targets a
// connected device, so a deploy build is filtered the same way.
val allAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val requestedAbis = providers.gradleProperty("abi").orNull
    ?: providers.gradleProperty("android.injected.build.abi").orNull
val requestedUniversal = requestedAbis
    ?.split(',')
    ?.any { it.trim().equals("universal", ignoreCase = true) || it.trim().equals("all", ignoreCase = true) }
    ?: false
val abiSelection: List<String> = requestedAbis
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.filterNot { it.equals("universal", ignoreCase = true) || it.equals("all", ignoreCase = true) }
    .orEmpty()
val debugAbis: List<String> = when {
    requestedAbis == null -> listOf("arm64-v8a")
    requestedUniversal -> allAbis
    else -> abiSelection
}
val releaseAbis: List<String> = if (abiSelection.isNotEmpty()) abiSelection else allAbis

android {
    namespace = "io.furryr.file"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.furryr.file"
        minSdk = 26
        targetSdk = 28
        // F-Droid reads these values with a regex and checks them against
        // the built APK. Keep them literal and bump both for every release;
        // a generated suffix (git hash, timestamp) breaks `fdroid
        // checkupdates` and the APK version check.
        versionCode = 10000
        versionName = "1.0.0"

        // A single-ABI build must not inherit AAR-provided libs for the other
        // ABIs (libandroidx.graphics.path.so ships all four). No filter means
        // universal: every ABI any source provides stays in the APK.
        if (abiSelection.isNotEmpty()) {
            ndk {
                abiFilters += abiSelection
            }
        }
    }

    // HiddenApiBypass (LSPosed) is used to reach android.system.Os.recvmsg so we
    // can read SCM_RIGHTS frames ourselves. Play would reject that.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        debug {
            // Keep the default debug loop to one ABI. Without this the
            // universal release filter would package AAR libs for ABIs whose
            // native daemon was not built.
            if (requestedAbis == null) {
                ndk {
                    abiFilters.addAll(debugAbis)
                }
            }
        }
        release {
            // Utilities ship as lib<name>.so, so extraction into
            // nativeLibraryDir no longer needs a debuggable APK.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.excludes.add("**/libtermux.so")
    }

    androidResources {
        // DevTools ships precompressed Brotli streams (`*.br`). Deflating an
        // already-compressed asset only burns packaging time.
        noCompress += "br"
    }

    lint {
        // targetSdk 28 is intentional: the app needs unrestricted storage and
        // root/container features. Play's target-level policy only applies to
        // store distribution; keep it from failing release lint.
        disable += "ExpiredTargetSdkVersion"
    }

    // Native utilities are packaged as lib/<abi>/lib<name>.so so Android
    // extracts them into nativeLibraryDir for both debuggable and release APKs.
}

// ── Native build hook ────────────────────────────────────────────────
// Gradle only packages what is already staged under src/main/jniLibs, so a
// Rust change (or the chroot SELinux backend) silently stayed out of the APK.
// Debug builds only the default device ABI (or -Pabi=...); release builds
// every ABI for a universal APK. -PskipNativeBuild skips both for IDE-only
// builds that reuse staged binaries.
val skipNativeBuild = flag("skipNativeBuild")

val nativeDir = rootProject.layout.projectDirectory.dir("native")
val scriptsDir = rootProject.layout.projectDirectory.dir("scripts")
val jniRoot = layout.projectDirectory.dir("src/main/jniLibs")
val assetsDir = layout.projectDirectory.dir("src/main/assets")

fun registerNativeBuild(name: String, abis: List<String>, descriptionText: String) =
    tasks.register<Exec>(name) {
        group = "build"
        description = descriptionText
        workingDir = rootProject.projectDir
        commandLine(
            "bash",
            scriptsDir.file("build-native.sh").asFile.absolutePath,
            *abis.toTypedArray(),
        )

        inputs.property("abis", abis.joinToString(","))
        inputs.files(
            scriptsDir.file("build-native.sh"),
            scriptsDir.file("fetch-xkbdata.sh"),
            rootProject.layout.projectDirectory.file("rust-toolchain.toml"),
            rootProject.layout.projectDirectory.file(".cargo/config.toml"),
            nativeDir.file("Cargo.toml"),
            nativeDir.file("Cargo.lock"),
            nativeDir.file("build.rs"),
            nativeDir.file("egl_shim.c"),
            nativeDir.file("wayland/Cargo.toml"),
            nativeDir.file("wayland/build.rs"),
            nativeDir.file("wayland/native/egl_shim.c"),
            nativeDir.file("run0/Cargo.toml"),
            nativeDir.file("here/Cargo.toml"),
            nativeDir.file("agent/Cargo.toml"),
            nativeDir.file("hub-client/Cargo.toml"),
            nativeDir.file("adblock/Cargo.toml"),
            nativeDir.file("build-libsepol.sh"),
            nativeDir.file("build-xkbcommon.sh"),
            nativeDir.file("build-android.sh"),
            nativeDir.file("build-android-aarch64.sh"),
            nativeDir.file("build-proot.sh"),
            nativeDir.file("patches/proot/LICENSE"),
            nativeDir.file("build-pulse.sh"),
            nativeDir.file("sepolicy/shim.c"),
            rootProject.layout.projectDirectory.file("proto/file_daemon.proto"),
        ).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.dir(nativeDir.dir("src"))
        inputs.dir(nativeDir.dir("wayland/src"))
        inputs.dir(nativeDir.dir("run0/src"))
        inputs.dir(nativeDir.dir("here/src"))
        inputs.dir(nativeDir.dir("agent/src"))
        inputs.dir(nativeDir.dir("hub-client/src"))
        inputs.dir(nativeDir.dir("adblock/src"))
        inputs.dir(nativeDir.dir("patches"))
        inputs.dir(nativeDir.dir("pulse"))

        outputs.dir(assetsDir.dir("xkb"))
        outputs.dir(jniRoot)
        outputs.files(
            assetsDir.file("native-licenses/LICENSE-libsepol"),
            assetsDir.file("native-licenses/LICENSE-PulseAudio"),
            assetsDir.file("native-licenses/LGPL-2.1"),
            assetsDir.file("native-licenses/COPYING-libsndfile"),
            assetsDir.file("native-licenses/COPYING-libltdl"),
            assetsDir.file("native-licenses/COPYING-talloc"),
        )
    }

val buildNative = registerNativeBuild(
    "buildNative",
    releaseAbis,
    "Builds every release native artifact (all ABIs) and stages them for packaging.",
)
val buildNativeDebug = registerNativeBuild(
    "buildNativeDebug",
    debugAbis,
    "Builds only the debug ABI selection and stages it for packaging.",
)

if (!skipNativeBuild) {
    tasks.configureEach {
        when (name) {
            "preDebugBuild" -> dependsOn(buildNativeDebug)
            "preReleaseBuild" -> dependsOn(buildNative)
        }
    }
}

// ── Release APK post-processing ──────────────────────────────────────
// AGP deflates every entry at zlib level 1 and aapt2 stores resources.arsc,
// so the packaged release APK is ~2 MB larger than a level-9 rewrite. The
// rewrite invalidates any signature, so recompress + zipalign + apksigner run
// as one step after packaging. The script signs with keystore.properties,
// ANDROID_KEYSTORE* env vars, or the debug keystore (sideload only).
// The input name assumes no AGP signingConfig; adding one changes it to
// app-release.apk and this task must be updated (the script strips and
// rebuilds signatures either way).
val postprocessReleaseApk = tasks.register<Exec>("postprocessReleaseApk") {
    group = "build"
    description = "Recompresses (Zopfli when available), aligns and signs the release APK."
    dependsOn("packageRelease")
    workingDir = rootProject.projectDir
    val unsignedApk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")
    val signedApk = layout.buildDirectory.file("outputs/apk/release/app-release-signed.apk")
    val postprocessScript = rootProject.layout.projectDirectory.file("scripts/postprocess-release.py")
    inputs.file(unsignedApk)
    inputs.file(postprocessScript)
    outputs.file(signedApk)
    commandLine(
        "python3",
        postprocessScript.asFile.absolutePath,
        unsignedApk.get().asFile.absolutePath,
        signedApk.get().asFile.absolutePath,
    )
}

// F-Droid signs the APK itself. Its build must stop at the unsigned
// package; recompressing and signing locally would produce an unexpected
// output file (and needs a keystore the build server does not have).
val skipPostprocess = flag("skipPostprocess")

// The assemble task is created by AGP after this script is evaluated.
tasks.configureEach {
    if (name == "assembleRelease" && !skipPostprocess) {
        finalizedBy(postprocessReleaseApk)
    }
}

// ── Chrome DevTools frontend assets ──────────────────────────────────
// tools/devtools pins the official frontend build; its output is generated
// into src/main/assets/devtools so the runtime bridge can serve it offline.
val skipDevToolsBuild = flag("skipDevToolsBuild")
val devToolsDir = rootProject.layout.projectDirectory.dir("tools/devtools")
val appResDir = rootProject.layout.projectDirectory.dir("app/src/main/res")

val installDevTools = tasks.register<Exec>("installDevTools") {
    group = "build"
    description = "Installs the pinned Chrome DevTools frontend npm package."
    workingDir = devToolsDir.asFile
    commandLine("npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund")

    inputs.file(devToolsDir.file("package.json"))
    inputs.file(devToolsDir.file("package-lock.json"))
    outputs.dir(devToolsDir.dir("node_modules"))
}

val syncDevTools = tasks.register<Exec>("syncDevTools") {
    group = "build"
    description = "Copies the pinned Chrome DevTools frontend into app assets."
    dependsOn(installDevTools)
    workingDir = devToolsDir.asFile
    commandLine("npm", "run", "sync")

    inputs.files(
        devToolsDir.file("package.json"),
        devToolsDir.file("package-lock.json"),
        devToolsDir.file("scripts/sync.mjs"),
    )
    // The sync stages one DevTools locale per app translation, so a new
    // values-* folder must invalidate this task even though no input file
    // under tools/devtools changed.
    inputs.property(
        "appLocaleDirs",
        providers.provider {
            appResDir.asFile.listFiles().orEmpty()
                .map { it.name }
                .filter { it.startsWith("values-") }
                .sorted()
                .joinToString(",")
        },
    )
    outputs.dir(assetsDir.dir("devtools"))
}

if (!skipDevToolsBuild) {
    tasks.named("preBuild").configure { dependsOn(syncDevTools) }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.instrumentation.transformClassesWith(
            JniStripClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) {}
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
        )
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.5")
    implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.5") {
        // Annotation-only at runtime. Its EPL-2.0 POM has no GPL secondary
        // license, and shipping it would make the release classpath
        // GPL-incompatible even though nothing needs it after compilation.
        exclude(group = "org.eclipse.jdt", module = "org.eclipse.jdt.annotation")
    }
    implementation(project(":agent"))
    implementation(project(":container"))
    implementation(project(":core"))
    implementation(project(":daemon"))
    implementation(project(":hub"))
    implementation(project(":proxy"))
    implementation(project(":provider"))
    implementation(project(":pulse"))
    implementation(project(":wayland"))
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    // Document-start userscript injection; DOCUMENT_START_SCRIPT exists since
    // 1.9, so this stays on the version that matches the app's compileSdk.
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("com.termux.termux-app:terminal-view:0.118.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ── ASM Bytecode Instrumentation ─────────────────────────────────────
// Strips JNI native methods from com.termux.terminal.JNI and redirects
// calls to io.furryr.file.util.ProcessHelper (daemon-backed). Also drops
// Termux's "[Process completed …]" screen append in the session handler:
// the app owns tab/connection state, so the banner must not overwrite the
// last transcript line on an SSH disconnect.

abstract class JniStripClassVisitorFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor = when (classContext.currentClassData.className) {
        "com.termux.terminal.TerminalSession\$MainThreadHandler" ->
            TerminalExitBannerVisitor(nextClassVisitor)
        "com.termux.view.textselection.TextSelectionCursorController\$1" ->
            TextSelectionActionModeVisitor(nextClassVisitor)
        "com.termux.terminal.TerminalSession\$1",
        "com.termux.terminal.TerminalSession\$2" ->
            TerminalStreamFactoryVisitor(nextClassVisitor)
        else -> JniClassVisitor(nextClassVisitor)
    }

    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className == "com.termux.terminal.JNI" ||
            classData.className == "com.termux.terminal.TerminalSession\$MainThreadHandler" ||
            classData.className == "com.termux.terminal.TerminalSession\$1" ||
            classData.className == "com.termux.terminal.TerminalSession\$2" ||
            classData.className == "com.termux.view.textselection.TextSelectionCursorController\$1"
}

/**
 * Replaces Termux's selection ActionMode menu (Copy/Paste/More) with the
 * app's browser-style actions. The injected call runs right before the original
 * `onCreateActionMode` returns true, after the library has built its menu;
 * `populate` clears it and adds the localized items. The same hook runs before
 * `onPrepareActionMode` returns, which Termux invalidates on every selection
 * change, so content-dependent rows (Open link) follow the current selection.
 */
class TextSelectionActionModeVisitor(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {

    override fun visitMethod(
        access: Int, name: String, descriptor: String,
        signature: String?, exceptions: Array<String>?
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        val hook = when (name) {
            "onCreateActionMode" -> "populate"
            "onPrepareActionMode" -> "refresh"
            else -> return mv
        }

        return object : MethodVisitor(Opcodes.ASM9, mv) {
            override fun visitInsn(opcode: Int) {
                if (opcode == Opcodes.IRETURN) {
                    visitVarInsn(Opcodes.ALOAD, 0)
                    visitFieldInsn(
                        Opcodes.GETFIELD,
                        "com/termux/view/textselection/TextSelectionCursorController\$1",
                        "this\$0",
                        "Lcom/termux/view/textselection/TextSelectionCursorController;",
                    )
                    visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "com/termux/view/textselection/TextSelectionCursorController",
                        "access\$000",
                        "(Lcom/termux/view/textselection/TextSelectionCursorController;)Lcom/termux/view/TerminalView;",
                        false,
                    )
                    visitVarInsn(Opcodes.ALOAD, 2)
                    visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "io/furryr/file/terminal/TerminalSelectionMenu",
                        hook,
                        "(Lcom/termux/view/TerminalView;Landroid/view/Menu;)V",
                        false,
                    )
                }
                super.visitInsn(opcode)
            }
        }
    }
}

/**
 * Removes the `mEmulator.append(banner)` call from the process-exited handler.
 * The banner bytes, the emulator and the length are on the stack before the
 * call; discarding all three leaves `notifyScreenUpdate` and
 * `onSessionFinished` untouched, so the tab still closes/overlays correctly
 * while the previous transcript stays intact.
 */
class TerminalExitBannerVisitor(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {

    override fun visitMethod(
        access: Int, name: String, descriptor: String,
        signature: String?, exceptions: Array<String>?
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (name != "handleMessage") return mv

        return object : MethodVisitor(Opcodes.ASM9, mv) {
            private var lastWasArrayLength = false

            override fun visitInsn(opcode: Int) {
                lastWasArrayLength = opcode == Opcodes.ARRAYLENGTH
                super.visitInsn(opcode)
            }

            override fun visitMethodInsn(
                opcode: Int, owner: String, name: String,
                descriptor: String, isInterface: Boolean
            ) {
                // The banner append is the one whose byte count comes from
                // `messageBytes.length`; the normal output append passes the
                // receive-buffer length local. Only drop the former.
                if (owner == "com/termux/terminal/TerminalEmulator" &&
                    name == "append" && descriptor == "([BI)V" && lastWasArrayLength
                ) {
                    visitInsn(Opcodes.POP) // byte count
                    visitInsn(Opcodes.POP) // banner bytes
                    visitInsn(Opcodes.POP) // emulator reference
                    lastWasArrayLength = false
                    return
                }
                lastWasArrayLength = false
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }
    }
}

/**
 * Replaces the `new FileInputStream(fd)` / `new FileOutputStream(fd)` in
 * TerminalSession's reader/writer threads with [TerminalBridge] stream
 * adapters. The descriptor only carries the terminal-process token, so the
 * session never touches an OS fd for I/O.
 */
class TerminalStreamFactoryVisitor(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {

    override fun visitMethod(
        access: Int, name: String, descriptor: String,
        signature: String?, exceptions: Array<String>?
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (name != "run") return mv

        return object : MethodVisitor(Opcodes.ASM9, mv) {
            private var skipNew: String? = null
            private var skipDup = false

            override fun visitTypeInsn(opcode: Int, type: String) {
                if (opcode == Opcodes.NEW &&
                    (type == "java/io/FileInputStream" || type == "java/io/FileOutputStream")
                ) {
                    skipNew = type
                    skipDup = true
                    return
                }
                super.visitTypeInsn(opcode, type)
            }

            override fun visitInsn(opcode: Int) {
                if (skipDup && opcode == Opcodes.DUP) {
                    skipDup = false
                    return
                }
                super.visitInsn(opcode)
            }

            override fun visitMethodInsn(
                opcode: Int, owner: String, name: String,
                descriptor: String, isInterface: Boolean
            ) {
                val skipped = skipNew
                if (opcode == Opcodes.INVOKESPECIAL &&
                    (owner == "java/io/FileInputStream" || owner == "java/io/FileOutputStream") &&
                    name == "<init>" && descriptor == "(Ljava/io/FileDescriptor;)V"
                ) {
                    skipNew = null
                    skipDup = false
                    val adapter = if (owner == "java/io/FileInputStream") "inputStream" else "outputStream"
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "io/furryr/file/terminal/TerminalBridge",
                        adapter,
                        "(Ljava/io/FileDescriptor;)Ljava/io/${if (owner == "java/io/FileInputStream") "FileInputStream" else "FileOutputStream"};",
                        false,
                    )
                    return
                }
                if (skipped != null) skipNew = null
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }
    }
}

class JniClassVisitor(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {

    override fun visitMethod(
        access: Int, name: String, descriptor: String,
        signature: String?, exceptions: Array<String>?
    ): MethodVisitor? {
        if (name == "<clinit>") return null
        if (access and Opcodes.ACC_NATIVE == 0) {
            return super.visitMethod(access, name, descriptor, signature, exceptions)
        }

        val newAccess = access and Opcodes.ACC_NATIVE.inv()
        val mv = super.visitMethod(newAccess, name, descriptor, signature, exceptions)
        mv.visitCode()

        val argTypes = Type.getArgumentTypes(descriptor)
        var idx = 0
        for (t in argTypes) {
            mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), idx)
            idx += t.size
        }
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "io/furryr/file/terminal/TerminalBridge",
            name,
            descriptor,
            false
        )
        mv.visitInsn(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN))
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        return null
    }
}
