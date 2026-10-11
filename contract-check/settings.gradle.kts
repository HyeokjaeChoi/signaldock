// Independent verification build for the OpenAPI contract (issue #1).
// Compiles the generated Kotlin DTOs and runs the JSON round-trip fixtures.
// Per Q89 builds stay independent; this one shares only the pinned versions.
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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "signaldock-contract-check"
