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

include(":config", ":types", ":masking", ":clickhouse", ":transfer", ":cli", ":it")
project(":config").projectDir = file("modules/config")
project(":types").projectDir = file("modules/types")
project(":clickhouse").projectDir = file("modules/clickhouse")
project(":transfer").projectDir = file("modules/transfer")
project(":masking").projectDir = file("modules/masking")
project(":cli").projectDir = file("modules/cli")
project(":it").projectDir = file("modules/it")
