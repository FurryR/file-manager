plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.furryr.file.wayland"
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
    // The compositor itself is native (native/wayland, Smithay) and ships as
    // jniLibs/arm64-v8a/libwayland_compositor.so; no prebuilt AAR stack.
}
