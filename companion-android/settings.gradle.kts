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
    }
}

rootProject.name = "A+ConnectCompanion"
include(":app")

// The clipboard privilege spike: a throwaway measuring tool, kept out of the
// shipping app on purpose.
include(":spike")
