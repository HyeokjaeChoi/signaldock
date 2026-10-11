// Independent build: Ktor ingestion server (issue #1 skeleton).
// Per Q89 the server and SDK are separate Gradle builds with their own
// settings and wrappers. Real modules land in issue #2.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // Shared pinned versions; each build still configures independently (Q89).
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "signaldock-server"
