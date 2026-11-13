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
        // Hammerhead publishes karoo-ext only to GitHub Packages, which wants a token even for a
        // public package. JitPack builds the same public repo from its tag with no credentials,
        // and is allowed to serve that one group and nothing else.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.hammerheadnav") }
        }
    }
}
rootProject.name = "RoadAhead"
// core: the engine, plain Kotlin, runs on the Mac. replay: a Mac-only tool that runs it over
// real roads and rides. app: the Karoo extension.
include(":core", ":replay", ":app")
