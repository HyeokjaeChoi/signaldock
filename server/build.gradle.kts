// Root project only coordinates. Code lives in :app, :ingestion, :persistence (ADR-0005).
// Plugin versions come from the version catalog here, so subprojects apply them by id only.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
