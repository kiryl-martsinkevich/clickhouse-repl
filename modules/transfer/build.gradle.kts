plugins {
    id("chrepl.kotlin-conventions")
    `java-library`
}

dependencies {
    api(project(":config"))
    api(project(":masking"))
    api(project(":clickhouse"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
