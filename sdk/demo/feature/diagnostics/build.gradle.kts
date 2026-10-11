// Demo module (ADR-0005). compileSdk 37 because Compose needs it (ADR-0006 decision 1);
// the SDK module itself stays at compileSdk 36.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "local.signaldock.demo.feature.diagnostics"
    compileSdk = libs.versions.demo.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":demo:core:data"))
    implementation(project(":demo:core:ui"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
