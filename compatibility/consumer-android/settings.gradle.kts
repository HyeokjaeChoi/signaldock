// Independent consumer app (issue 06, spec section 6 "Reproduction and deliverables").
// It is not included in the sdk/ build. It gets the SDK only by Maven coordinate from an
// unzipped folder repository: -Psignaldock.repo=<folder> (see scripts/verify-consumer.sh).
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
        // Only local.signaldock comes from the folder. A missing property fails the build.
        maven {
            url = uri(
                providers.gradleProperty("signaldock.repo").orNull
                    ?: error("Set -Psignaldock.repo=<unzipped Maven folder>"),
            )
            content { includeGroup("local.signaldock") }
        }
    }
    // Shared tool pins only (Q89). The SDK is not a project dependency.
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "signaldock-consumer"
include(":app")
