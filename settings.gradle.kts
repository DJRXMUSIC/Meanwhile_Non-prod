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

rootProject.name = "MeanwhileV4"

// `domain` is a standalone pure-Kotlin build (no Android) so the dose engine and its golden
// tests build anywhere with just a JDK: `./gradlew -p domain test`.
includeBuild("domain")
include(":app")
