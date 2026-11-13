plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "au.com.fjellconsulting.roadahead"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.com.fjellconsulting.roadahead"
        // The Karoo 2 runs Android 8 (API 26), the Karoo 3 Android 12 (API 32).
        minSdk = 26
        // Deliberately old. The Karoo keeps its offline maps in a folder only the storage
        // permission's group can read, and an app that targets API 28 can still ask the rider
        // for that permission with the ordinary prompt. A newer target would need a cable and
        // an adb command, which rules out anyone who isn't at a desk with a Mac.
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // The engine: road finding, road following, heights and the picture. It brings the
    // Mapsforge map reader with it.
    implementation(project(":core"))
    // The Karoo SDK, Hammerhead's own 1.1.9 tag, built by JitPack (see settings.gradle.kts).
    implementation("com.github.hammerheadnav:karoo-ext:1.1.9")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
