plugins {
    id("chrepl.kotlin-conventions")
    `java-library`
}

dependencies {
    api(project(":guard"))
    api(platform(libs.spring.boot.bom))
    implementation(libs.postgresql)
    implementation(libs.slf4j.api)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.jsr310)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
