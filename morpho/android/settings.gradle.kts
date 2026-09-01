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
        // Tesseract4Android (on-device OCR) publishes through JitPack only.
        maven("https://jitpack.io") {
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "morpho-android"

// The conversion engine is a separate pure-JVM build with no Android
// dependency; composite inclusion substitutes app.morpho.engine:* below.
includeBuild("../engine")

include(":app")
include(":core:design")
// Path is :pdf (not :engine:pdf): a project named "engine" would collide
// with the included engine build, whose name comes from its directory.
include(":pdf")
