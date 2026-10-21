plugins {
    id("chrepl.kotlin-conventions")
    `java-library`
}

dependencies {
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)
    implementation(libs.jackson.yaml)
    implementation(libs.jackson.kotlin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    systemProperty("chrepl.exampleConfig", rootProject.file("docs/examples/replicator.yaml").absolutePath)
}
