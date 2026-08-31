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

rootProject.name = "morpho-android"

// The conversion engine is a separate pure-JVM build with no Android
// dependency; composite inclusion substitutes app.morpho.engine:* below.
includeBuild("../engine")

include(":app")
include(":core:design")
include(":engine:pdf")
