pluginManagement {
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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TB-Chat"

include(":app")

include(":domain")

include(":core:common")
include(":core:designsystem")
include(":core:data")
include(":core:device")

include(":inference:api")
include(":inference:llamacpp")
include(":inference:service")

include(":feature:home")
include(":feature:chat")
include(":feature:models")
include(":feature:downloads")
include(":feature:settings")
