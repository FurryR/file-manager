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
    // The wldroid compositor stack ships prebuilt natives (wlroots Android
    // backend, virgl_test_server, guest shims); see scripts/fetch-wldroid.sh.
    implementation("nu.shel.wldroid:wldroid-compositor:0.0.5")
    implementation("nu.shel.wldroid:wldroid-virgl:0.0.5")
    implementation("nu.shel.wldroid:wldroid-shims:0.0.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
