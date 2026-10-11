// Independent build: Ktor ingestion server.
// Per Q89 the server and SDK are separate Gradle builds with their own
// settings and wrappers.
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
// Layered modules: ADR-0005, section "Server: layered Gradle modules".
include(":app", ":ingestion", ":persistence")
