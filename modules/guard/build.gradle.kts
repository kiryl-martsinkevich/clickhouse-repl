plugins {
    id("chrepl.kotlin-conventions")
    `java-library`
}

dependencies {
    api(project(":transfer"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    systemProperty("chrepl.exampleConfig", rootProject.file("docs/examples/replicator.yaml").absolutePath)
}
