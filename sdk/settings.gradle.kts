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
