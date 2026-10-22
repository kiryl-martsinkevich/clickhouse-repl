plugins {
    id("chrepl.kotlin-conventions")
    `java-library`
    alias(libs.plugins.jmh)
}

dependencies {
    api(project(":config"))
    implementation(libs.bouncycastle)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotest.property)
    testImplementation(libs.coroutines.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    systemProperty("chrepl.exampleConfig", rootProject.file("docs/examples/replicator.yaml").absolutePath)
}

jmh {
    jmhVersion.set(libs.versions.jmh)
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    timeOnIteration.set("3s")
    warmup.set("2s")
    resultFormat.set("JSON")
}

// The benchmark loads the example config (dictionaries, domains, trades spec).
jmh {
    jvmArgsAppend.set(listOf("-Dchrepl.exampleConfig=${rootProject.file("docs/examples/replicator.yaml").absolutePath}"))
}
