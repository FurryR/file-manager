import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.google.protobuf.gradle.proto
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main") {
        proto {
            srcDir("../proto")
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.excludes.add("**/libtermux.so")
    }
}

val buildNativeDaemon = tasks.register<Exec>("buildNativeDaemon") {
    group = "build"
    description = "Builds libfiledaemon.so and copies it into app/src/main/jniLibs/arm64-v8a before Android packaging."
    workingDir = rootProject.projectDir
    commandLine("bash", rootProject.file("native/build-android-aarch64.sh").absolutePath)

    inputs.file(rootProject.file("native/build-android-aarch64.sh"))
    inputs.file(rootProject.file("native/Cargo.toml"))
    inputs.file(rootProject.file("native/Cargo.lock"))
    inputs.file(rootProject.file("native/build.rs"))
    inputs.dir(rootProject.file("native/src"))
    inputs.file(rootProject.file("proto/file_daemon.proto"))

    outputs.file(rootProject.file("app/src/main/jniLibs/arm64-v8a/libfiledaemon.so"))
}

tasks.named("preBuild").configure {
    dependsOn(buildNativeDaemon)
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

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.28.2"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.protobuf:protobuf-javalite:4.28.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.termux.termux-app:terminal-view:0.118.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
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
