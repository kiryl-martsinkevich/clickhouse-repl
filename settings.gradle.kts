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

include(":config", ":cli")
project(":config").projectDir = file("modules/config")
project(":cli").projectDir = file("modules/cli")
