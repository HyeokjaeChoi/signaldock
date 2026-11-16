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
    alias(libs.plugins.detekt) apply false
}

// Static analysis for every module (ADR-0049 decisions 2 and 3). Each module has a committed baseline that may only
// shrink (scripts/check-repo-rules.sh).
//   sdk/gradlew -p sdk lint detekt     fails on a finding that is not in a baseline
//   sdk/gradlew -p sdk updateLintBaseline detektBaseline    rewrites the baselines (only to record a change you
//                                      cannot avoid; say why in the PR)
subprojects {
    // Android Lint: warnings are errors. `lint-baseline.xml` is committed in each Android module.
    plugins.withType<com.android.build.gradle.api.AndroidBasePlugin> {
        extensions.configure<com.android.build.api.dsl.CommonExtension> {
            lint.warningsAsErrors = true
            lint.abortOnError = true
            lint.textReport = true
            lint.baseline = file("lint-baseline.xml")
            // Checks whose result depends on something outside the repo: the newest version on the network, the date
            // of a Play deadline, the state of the Play SDK Index. A green build would turn red with no change in
            // the repo. Renovate owns freshness (ADR-0031).
            lint.disable += setOf(
                "AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable",
                "ExpiringTargetSdkVersion", "ExpiredTargetSdkVersion",
                "OutdatedLibrary", "PlaySdkIndexNonCompliant", "RiskyLibrary",
            )
        }
    }
    // detekt with the ktlint rule set; one config for sdk and server (gradle/detekt.yml). It reads all source sets
    // of the module (main, test, androidTest, measure).
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("../gradle/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        source.setFrom("src")
    }
    dependencies { "detektPlugins"(rootProject.libs.detekt.ktlint) }
}
