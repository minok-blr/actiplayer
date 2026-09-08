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

rootProject.name = "actiplayer"

// :engine is pure Kotlin and must stay that way (CLAUDE.md hard rules). Everything
// Android-facing hangs off :data, which nothing else in the graph depends back on.
include(":app", ":engine", ":data", ":sensors", ":playback", ":session")
