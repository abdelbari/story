// Morpho conversion engine — a pure-JVM build with no Android dependency.
// This separation is deliberate (see ../README.md): the engine must never
// depend on Android APIs, and keeping it a standalone build enforces that.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "morpho-engine"

include(":layout")
include(":ooxml")
include(":pdf-read")
