plugins {
    id("chrepl.kotlin-conventions")
    application
}

dependencies {
    implementation(project(":config"))
    implementation(project(":transfer"))
    implementation(project(":guard"))
    implementation(libs.clikt)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "clickhouse-repl"
    mainClass.set("chrepl.cli.MainKt")
}

tasks.test {
    systemProperty("chrepl.exampleConfig", rootProject.file("docs/examples/replicator.yaml").absolutePath)
}

distributions {
    main {
        distributionBaseName.set("clickhouse-repl")
    }
}
