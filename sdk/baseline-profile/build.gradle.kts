// Baseline Profile generator and startup benchmark (issue 70, ADR-0040). A test module that drives :profile-app
// on an emulator or device. Never published or shipped.
//   sdk/gradlew -p sdk :sdk-android:generateBaselineProfile   writes sdk-android/src/main/generated/baselineProfiles/
//   sdk/gradlew -p sdk :baseline-profile:connectedBenchmarkReleaseAndroidTest   startup with and without the profile
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "local.signaldock.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 28 // Macrobenchmark and the generator need API 28 or newer.
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":profile-app"
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

baselineProfile {
    // A connected emulator or device. No Gradle Managed Device: the project uses its own AVD.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit)
}
