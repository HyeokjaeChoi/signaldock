// Root project only coordinates. Code lives in :app, :ingestion, :query, :persistence (ADR-0005).
// Plugin versions come from the version catalog here, so subprojects apply them by id only.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}

// detekt with the ktlint rule set for every module (ADR-0049 decision 3). One config for sdk and server
// (gradle/detekt.yml). Each module has a committed detekt-baseline.xml that may only shrink
// (scripts/check-repo-rules.sh). `gradlew detekt` fails on a finding that is not in the baseline;
// `gradlew detektBaseline` rewrites it (do that only to record a change you cannot avoid, and say why in the PR).
subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("../gradle/detekt.yml"))
        baseline = file("detekt-baseline.xml")
    }
    dependencies { "detektPlugins"(rootProject.libs.detekt.ktlint) }
}
