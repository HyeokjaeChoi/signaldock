// Independent build: Android SDK (issue #1 skeleton).
// Per Q89 the SDK and server are separate Gradle builds with their own
// settings and wrappers. The root scripts/ connect contract generation
// and verification across them.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    // Shared pinned versions; each build still configures independently (Q89).
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "signaldock-sdk"
include(":sdk-android")
// R8 release measurement app (issue 33, ADR-0018).
include(":measure-app")
// Baseline Profile (issue 70, ADR-0040): the sample app, and the test module that generates and measures.
include(":profile-app", ":baseline-profile")
// Demo app (ADR-0005 "Demo app: feature modules and unidirectional data flow").
// Intermediate projects :demo, :demo:feature and :demo:core have no build file.
include(":demo:app", ":demo:feature:shop", ":demo:feature:diagnostics", ":demo:core:ui", ":demo:core:data")
