// The one SDK publication module (Q86). Public API in `local.signaldock`;
// implementation in `local.signaldock.internal`. No DI framework (Q87).
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "local.signaldock"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // Consumers must compile against API 36 or newer (this SDK is built and tested at 36).
        aarMetadata.minCompileSdk = libs.versions.compileSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Publish the release variant with a sources jar (issue 06). The AAR ships no consumer R8 rules
    // (ADR-0009 decision 6); add consumerProguardFiles only when a run shows a missing rule.
    publishing {
        singleVariant("release") { withSourcesJar() }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric SDK 36 runs on JDK 21 and reads FileDescriptor internals.
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

// Built-in Kotlin (AGP 9): no kotlin-android plugin. Bytecode target 17 stays below
// the JDK 21 that runs the build, and AGP desugaring covers minSdk 24.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Generated models are `internal` (codegen nonPublicApi=true); PublicApiTest guards this.
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("../../generated/kotlin/src/main/kotlin")
    }
}

// Local Maven folder repository and ZIP (issue 06, spec Q126). Not Maven Central.
//   sdk/gradlew -p sdk :sdk-android:signaldockMavenZip
// writes sdk-android/build/distributions/signaldock-sdk-android-0.1.0-maven.zip.
val sdkVersion = "0.1.0"
val mavenFolder = layout.buildDirectory.dir("maven-repo")
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "local.signaldock"
            artifactId = "sdk-android"
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "folder"
            url = uri(mavenFolder)
        }
    }
}
tasks.register<Delete>("cleanMavenFolder") { delete(mavenFolder) }
tasks.matching { it.name == "publishReleasePublicationToFolderRepository" }.configureEach {
    mustRunAfter("cleanMavenFolder")
}
tasks.register<Zip>("signaldockMavenZip") {
    group = "publishing"
    description = "Publishes local.signaldock:sdk-android:$sdkVersion to a clean folder repository and zips it."
    dependsOn("cleanMavenFolder", "publishReleasePublicationToFolderRepository")
    from(mavenFolder)
    archiveBaseName.set("signaldock-sdk-android")
    archiveVersion.set(sdkVersion)
    archiveClassifier.set("maven")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

ksp {
    // Exported Room schema is committed so migrations can be reviewed.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.retrofit)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.metadata.jvm)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
}
