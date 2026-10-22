plugins {
    id("chrepl.kotlin-conventions")
    `java-library`
}

dependencies {
    api(project(":types"))
    implementation(platform(libs.jackson.bom))
    api(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    // Optional live check against a running server, e.g. -Dchrepl.devClickHouse=localhost:18123
    System.getProperty("chrepl.devClickHouse")?.let { systemProperty("chrepl.devClickHouse", it) }
}
