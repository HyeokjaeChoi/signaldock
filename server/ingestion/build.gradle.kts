// Pure Kotlin/JVM: no Ktor, jOOQ, Flyway, JDBC, serialization, or generated sources (ADR-0005).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories { mavenCentral() }

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("started", "passed", "failed", "skipped") }
}
