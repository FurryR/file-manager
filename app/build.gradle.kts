import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.furryr.file"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.furryr.file"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    // HiddenApiBypass (LSPosed) is used to reach android.system.Os.recvmsg so we
    // can read SCM_RIGHTS frames ourselves. Play would reject that.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isDebuggable = true
            isMinifyEnabled = false
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

    // Package standalone executables before signing, preserving lib/<abi>/<name>.
    // The JNI input pipeline filters out filenames without a .so suffix.
    sourceSets.getByName("main").resources.srcDir("src/main/executables")
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
    implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.5")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("com.termux.termux-app:terminal-view:0.118.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("commons-net:commons-net:3.13.0")
    implementation("com.github.mwiede:jsch:2.28.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ── ASM Bytecode Instrumentation ─────────────────────────────────────
// Strips JNI native methods from com.termux.terminal.JNI and redirects
// calls to io.furryr.file.util.ProcessHelper (daemon-backed).

abstract class JniStripClassVisitorFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor = JniClassVisitor(nextClassVisitor)

    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className == "com.termux.terminal.JNI"
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
            "io/furryr/file/util/ProcessHelper",
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
