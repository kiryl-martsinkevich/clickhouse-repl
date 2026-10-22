plugins {
    id("chrepl.kotlin-conventions")
}

dependencies {
    testImplementation(project(":transfer"))
    testImplementation(project(":guard"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Integration tests need Docker; run them with: ./gradlew :it:test -Pit [-Pchrepl.it.image=clickhouse/clickhouse-server:26.8]
tasks.test {
    enabled = project.hasProperty("it")
    systemProperty("chrepl.exampleConfig", rootProject.file("docs/examples/replicator.yaml").absolutePath)
    systemProperty("chrepl.drDdl", rootProject.file("deploy/dr-ddl.sql").absolutePath)
    (project.findProperty("chrepl.it.image") as String?)?.let { systemProperty("chrepl.it.image", it) }
    maxParallelForks = 1
    testLogging { events("passed", "skipped", "failed") }
}
