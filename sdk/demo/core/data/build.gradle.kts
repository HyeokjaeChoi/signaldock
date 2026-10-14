// Demo module (ADR-0005). compileSdk 37 because Compose needs it (ADR-0006 decision 1);
// the SDK module itself stays at compileSdk 36.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "local.signaldock.demo.core.data"
    compileSdk = libs.versions.demo.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Typed event functions generated from tracking-plan/plan.json (ADR-0021). Optional use of the SDK:
// they only call SignalDock.track. The consumer app does not use them.
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("../../../../tracking-plan/generated/kotlin")
    }
}

dependencies {
    api(project(":sdk-android"))
    implementation(libs.kotlinx.coroutines.android)
}
