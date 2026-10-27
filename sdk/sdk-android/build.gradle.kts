// The one SDK publication module (Q86). Public API in `local.signaldock`;
// implementation in `local.signaldock.internal`. No DI framework (Q87).
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dependency.guard)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "local.signaldock"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // Consumers must compile against API 36 or newer (this SDK is built and tested at 36).
        aarMetadata.minCompileSdk = libs.versions.compileSdk.get().toInt()
        // Instrumented durability tests (issue 20, ADR-0011). Not run in CI; only compiled.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // The measurement phases are shared with the R8 release measure app (issue 33, ADR-0018).
    // (added to the androidTest variant sources in `androidComponents` below)

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric SDK 36 runs on JDK 21 and reads FileDescriptor internals.
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

// Built-in Kotlin (AGP 9): no kotlin-android plugin. Bytecode target 17 stays below
// the JDK 21 that runs the build, and AGP desugaring covers minSdk 24.
// languageVersion/apiVersion 2.2 (spec Q132, ADR-0009): the 2.4 compiler writes metadata 2.2, so
// consumers on AGP 9's default built-in Kotlin (2.2) can read the AAR. Raise when AGP's default moves.
kotlin {
    // The POM's kotlin-stdlib dependency must also be readable by Kotlin 2.2 (stdlib 2.4 has metadata 2.4).
    coreLibrariesVersion = "2.2.21"
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

// Generated models are `internal` (codegen nonPublicApi=true); PublicApiTest guards this.
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("../../generated/kotlin/src/main/kotlin")
        variant.androidTest?.sources?.kotlin?.addStaticSourceDirectory("src/measure/kotlin")
    }
}

// Local Maven folder repository and ZIP (issue 06, spec Q126). Not Maven Central.
//   sdk/gradlew -p sdk :sdk-android:signaldockMavenZip
// writes sdk-android/build/distributions/signaldock-sdk-android-0.3.0-maven.zip.
val sdkVersion = "0.4.0"
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
    // Apache-2.0 Section 4(a): recipients of the ZIP get a copy of the license (issue 32).
    from(rootProject.file("../LICENSE"))
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

    // Baseline Profile (issue 70, ADR-0040). The generator module, not a runtime dependency.
    baselineProfile(project(":baseline-profile"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

// Public API check (issue 35, ADR-0019). The Kotlin Gradle plugin's abiValidation dumps nothing for
// this module, and the binary-compatibility-validator plugin registers no tasks: both look for a
// Kotlin plugin, and AGP built-in Kotlin applies none. The validator's task classes are wired by hand
// to the release classes.
//   sdk/gradlew -p sdk :sdk-android:apiCheck   fails when the public API differs from api/sdk-android.api
//   sdk/gradlew -p sdk :sdk-android:apiDump    rewrites api/sdk-android.api (intentional API change)
val apiDumpFile = layout.projectDirectory.file("api/sdk-android.api")
val apiBuild = tasks.register<kotlinx.validation.KotlinApiBuildTask>("apiBuild") {
    inputClassesDirs.from(tasks.named("compileReleaseKotlin").map { it.outputs.files })
    outputApiFile.set(layout.buildDirectory.file("api/sdk-android.api"))
}
tasks.register<kotlinx.validation.KotlinApiCompareTask>("apiCheck") {
    group = "verification"
    description = "Fails when the public API of the release classes differs from api/sdk-android.api."
    projectApiFile.set(apiDumpFile)
    generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
}
tasks.register<Copy>("apiDump") {
    group = "other"
    description = "Rewrites api/sdk-android.api from the release classes."
    from(apiBuild.flatMap { it.outputApiFile })
    into(apiDumpFile.asFile.parentFile)
}

// Published dependency baseline (issue 56, ADR-0033).
//   sdk/gradlew -p sdk :sdk-android:dependencyGuard          fails when the trees differ from dependencies/
//   sdk/gradlew -p sdk :sdk-android:dependencyGuardBaseline  rewrites dependencies/ (intentional change)
dependencyGuard {
    configuration("releaseRuntimeClasspath")
    configuration("releaseCompileClasspath")
}

// Baseline Profile (issue 70, ADR-0040). The plugin writes src/main/generated/baselineProfiles/baseline-prof.txt
// (committed). AGP packs it into the AAR, and the AGP of a host app merges it into the app profile.
//   sdk/gradlew -p sdk :sdk-android:generateBaselineProfile   needs one booted emulator or device
baselineProfile {
    // SDK classes only. Room, WorkManager, OkHttp, and Retrofit bring their own profiles. The sample app
    // is in the same package tree (local.signaldock.profileapp), so it is excluded.
    filter {
        include("local.signaldock.**")
        exclude("local.signaldock.profileapp.**")
    }
}
