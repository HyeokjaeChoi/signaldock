plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

application { mainClass.set("local.signaldock.server.ApplicationKt") }

val codegen by sourceSets.creating

sourceSets {
    main {
        kotlin.srcDir("../generated/kotlin/src/main/kotlin")
        java.srcDir("generated/jooq")
    }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.jooq)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)

    add(codegen.implementationConfigurationName, libs.flyway.core)
    add(codegen.implementationConfigurationName, libs.flyway.postgresql)
    add(codegen.implementationConfigurationName, libs.postgresql)
    add(codegen.implementationConfigurationName, libs.jooq.codegen)
    add(codegen.implementationConfigurationName, libs.testcontainers.postgresql)
    add(codegen.runtimeOnlyConfigurationName, libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
    environment("DOCKER_API_VERSION", "1.40")
    testLogging { events("started", "passed", "failed", "skipped") }
}

val generatedJooqDir = layout.projectDirectory.dir("generated/jooq")

tasks.register<JavaExec>("generateJooq") {
    group = "code generation"
    description = "Migrates an isolated PostgreSQL container and updates tracked jOOQ sources."
    dependsOn(tasks.named(codegen.classesTaskName))
    classpath = codegen.runtimeClasspath
    mainClass.set("local.signaldock.server.codegen.JooqCodegen")
    args(generatedJooqDir.asFile.absolutePath)
    environment("DOCKER_API_VERSION", "1.40")
}

tasks.register<JavaExec>("verifyJooq") {
    group = "verification"
    description = "Regenerates jOOQ sources in an empty temporary directory and compares all files."
    dependsOn(tasks.named(codegen.classesTaskName))
    classpath = codegen.runtimeClasspath
    mainClass.set("local.signaldock.server.codegen.JooqCodegen")
    val temporary = layout.buildDirectory.dir("verify-jooq")
    doFirst { delete(temporary) }
    args(temporary.get().asFile.absolutePath, generatedJooqDir.asFile.absolutePath)
    environment("DOCKER_API_VERSION", "1.40")
}

tasks.check { dependsOn("verifyJooq") }
