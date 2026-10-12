// Demo application (ADR-0005): entry point, navigation, manual composition. No DI framework (Q87).
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Endpoint and collection key come from -P properties or sdk/local.properties (gitignored).
// No key is committed. Debug defaults to the emulator host address; release has no default.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun setting(name: String): String? = providers.gradleProperty(name).orNull ?: local.getProperty(name)
fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "local.signaldock.demo.app"
    compileSdk = libs.versions.demo.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "local.signaldock.demo"
        minSdk = libs.versions.minSdk.get().toInt()
        // targetSdk 36 on purpose: Google Play requires 36 and has announced no API 37 deadline
        // (ADR-0006 decision 1). The API 37 emulator still runs it. compileSdk is 37 for Compose.
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            buildConfigField("String", "SIGNALDOCK_ENDPOINT", quoted(setting("signaldock.endpoint") ?: "http://10.0.2.2:8080"))
            buildConfigField("String", "SIGNALDOCK_COLLECTION_KEY", quoted(setting("signaldock.collectionKey") ?: ""))
        }
        release {
            buildConfigField("String", "SIGNALDOCK_ENDPOINT", quoted(setting("signaldock.endpoint") ?: "https://signaldock.invalid"))
            buildConfigField("String", "SIGNALDOCK_COLLECTION_KEY", quoted(setting("signaldock.collectionKey") ?: ""))
        }
    }

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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":sdk-android"))
    implementation(project(":demo:core:data"))
    implementation(project(":demo:core:ui"))
    implementation(project(":demo:feature:shop"))
    implementation(project(":demo:feature:diagnostics"))
}
