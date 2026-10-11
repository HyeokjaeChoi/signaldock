plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

application { mainClass.set("local.signaldock.server.ApplicationKt") }

sourceSets {
    // Generated transport DTOs are internal and compile only in this module (ADR-0005).
    main { kotlin.srcDir("../../generated/kotlin/src/main/kotlin") }
}

dependencies {
    implementation(project(":ingestion"))
    implementation(project(":persistence"))
    implementation(project(":query"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.jooq)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}

tasks.test {
    useJUnitPlatform()
    environment("DOCKER_API_VERSION", "1.40")
    testLogging { events("started", "passed", "failed", "skipped") }
}
