// R8 release measurement app (issue 33, ADR-0018). No UI. It holds the SDK as a normal dependency and runs
// the shared measurement phases (src/measure/kotlin of :sdk-android) from an Instrumentation, so the SDK
// and the measurement code go through R8 together, like a consumer app. Never published or shipped.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "local.signaldock.measureapp"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "local.signaldock.measureapp"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // A debug-signed release variant: no release keystore exists in this repository.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

androidComponents {
    onVariants { variant -> variant.sources.kotlin?.addStaticSourceDirectory("../sdk-android/src/measure/kotlin") }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":sdk-android"))
    implementation(libs.kotlinx.coroutines.android)
}
