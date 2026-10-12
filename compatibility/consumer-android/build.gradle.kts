// The SDK is compiled with Kotlin 2.4 (metadata 2.4.0). AGP 9.3.3 built-in Kotlin defaults to
// 2.2.0, which cannot read it ("compiled with an incompatible version of Kotlin"). A consumer
// must raise the Kotlin Gradle plugin to 2.4.x or newer; see ADR-0009. Keep this in step with
// `kotlin` in gradle/libs.versions.toml.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
