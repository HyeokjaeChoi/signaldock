// Consumer of local.signaldock:sdk-android:0.1.0 from a folder repository (no source dependency).
// compileSdk 36 / minSdk 24 / targetSdk 36 as the SDK requires (ADR-0006 decision 1).
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Endpoint and collection key come from -P properties or local.properties (gitignored). No key is committed.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun setting(name: String): String? = providers.gradleProperty(name).orNull ?: local.getProperty(name)
// -Psignaldock.sdk=false builds the baseline of the host-cost measurement (issue 36, ADR-0020): the same
// app with no SDK dependency and no SDK call (src/nosdk instead of src/sdk). Default: with the SDK.
val withSdk = providers.gradleProperty("signaldock.sdk").orNull != "false"
fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "local.signaldock.consumer"
    compileSdk = 36

    defaultConfig {
        applicationId = "local.signaldock.consumer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SIGNALDOCK_ENDPOINT", quoted(setting("signaldock.endpoint") ?: "http://10.0.2.2:8080"))
        buildConfigField("String", "SIGNALDOCK_COLLECTION_KEY", quoted(setting("signaldock.collectionKey") ?: ""))
    }

    // The baseline gets its own application ID, so that both variants can be installed side by side.
    if (!withSdk) defaultConfig.applicationIdSuffix = ".nosdk"

    sourceSets.getByName("main").kotlin.directories.add(if (withSdk) "src/sdk/kotlin" else "src/nosdk/kotlin")

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            // R8 is the point of this app. No keep rules of its own: the SDK and its libraries must
            // bring theirs (consumer rules in their AARs and jars).
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Verification app only: debug key so the release APK installs on an emulator.
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
    // scripts/verify-consumer.sh passes the SDK version; 0.1.0 is the default for a manual build.
    if (withSdk) {
        implementation("local.signaldock:sdk-android:${providers.gradleProperty("signaldock.version").getOrElse("0.1.0")}")
        implementation(libs.kotlinx.coroutines.android)
    }
}
