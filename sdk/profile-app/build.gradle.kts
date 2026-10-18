// Baseline Profile sample app (issue 70, ADR-0040). It uses the SDK as a normal dependency and runs its hot
// paths (initialize, track, Room queue, sender) once at start. :baseline-profile drives it to generate the
// profile for :sdk-android, and to measure startup with and without the profile. Never published or shipped.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "local.signaldock.profileapp"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "local.signaldock.profileapp"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 as in a host app. No keep rules of its own: the SDK must bring its own (ADR-0009).
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // A debug-signed release variant: no release keystore exists in this repository.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":sdk-android"))
    implementation(libs.kotlinx.coroutines.android)
    // The Macrobenchmark library installs the profile through this library. A host app that is not
    // installed from Google Play needs it too (ADR-0040 decision 4).
    implementation(libs.androidx.profileinstaller)
}
