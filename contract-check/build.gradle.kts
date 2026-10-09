// Compiles generated Kotlin DTOs and runs fixture round-trip tests.
// Generated sources live in ../generated/kotlin (tracked in Git per Q91);
// this build only reads them, never overwrites.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin.srcDir("../generated/kotlin/src/main/kotlin")
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
