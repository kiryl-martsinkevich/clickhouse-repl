// A development-only OIDC provider for the portal E2E tests and the demo. Never deploy it.
plugins {
    id("chrepl.kotlin-conventions")
    application
}

dependencies {
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "chrepl-devidp"
    mainClass.set("chrepl.devidp.DevIdpKt")
}
