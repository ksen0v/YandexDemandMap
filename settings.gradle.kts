pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // osmdroid's own artifacts are on Maven Central, no extra repo needed.
    }
}

rootProject.name = "YandexDemandMap"
include(":app")
