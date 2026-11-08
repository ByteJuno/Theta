pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "theta-core"

include(":app")
include(":sdk")
include(":samples:hello")
include(":samples:probe")
include(":samples:fixtures")
include(":samples:hook-target")
include(":samples:hook-fixture")
include(":samples:blocked-provider")
include(":samples:storage-fixture")
