// The Spring Boot plugin's buildpack support pulls commons-lang3 3.16 (GHSA-j288-q9x7-2f5v) onto the
// build classpath; require the patched version.
buildscript {
    dependencies {
        constraints {
            classpath("org.apache.commons:commons-lang3:3.21.0")
        }
    }
}

plugins {
    id("chrepl.spring-conventions")
    alias(libs.plugins.spring.boot)
}

val portal by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // -PskipPortal builds the app without the SPA (no Node.js needed).
    if (!project.hasProperty("skipPortal")) portal(project(path = ":portal", configuration = "dist"))
    implementation(project(":control"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.jackson3.kotlin)
    implementation(libs.springdoc.webmvc.ui)
    implementation(libs.postgresql)
    runtimeOnly(libs.micrometer.prometheus)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    from(portal) { into("static") }
}

tasks.bootJar {
    archiveFileName.set("clickhouse-repl-app.jar")
}
