plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.furryr.file.container"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":daemon"))
    implementation(project(":pulse"))
    implementation(project(":wayland"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
