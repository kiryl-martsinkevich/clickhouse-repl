pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "clickhouse-repl"

include(":config", ":types", ":masking", ":clickhouse", ":transfer", ":guard", ":control", ":app", ":portal", ":devidp", ":cli", ":it")
project(":config").projectDir = file("modules/config")
project(":types").projectDir = file("modules/types")
project(":clickhouse").projectDir = file("modules/clickhouse")
project(":transfer").projectDir = file("modules/transfer")
project(":guard").projectDir = file("modules/guard")
project(":control").projectDir = file("modules/control")
project(":app").projectDir = file("modules/app")
project(":portal").projectDir = file("modules/portal")
project(":devidp").projectDir = file("modules/devidp")
project(":masking").projectDir = file("modules/masking")
project(":cli").projectDir = file("modules/cli")
project(":it").projectDir = file("modules/it")
