plugins {
    id("chrepl.spring-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
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

tasks.bootJar {
    archiveFileName.set("clickhouse-repl-app.jar")
}
