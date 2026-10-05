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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Dot"

include(":app")
include(":core:auth")
include(":core:crypto")
include(":core:data")
include(":core:designsystem")
include(":core:domain")
include(":core:sync")
include(":core:ui")
include(":feature:account")
include(":feature:reminders")
include(":feature:settings")
include(":feature:tasks")
include(":feature:voice")
include(":feature:widget")
