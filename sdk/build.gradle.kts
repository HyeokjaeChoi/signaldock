// Independent SDK build (Q89). One publication module: :sdk-android (Q86).
// Versions are pinned in the root gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Public API check (issue 35, ADR-0019). Only its task classes are used, by :sdk-android;
    // the plugin finds no Kotlin plugin under AGP built-in Kotlin and registers no tasks itself.
    alias(libs.plugins.binary.compatibility.validator)
}
