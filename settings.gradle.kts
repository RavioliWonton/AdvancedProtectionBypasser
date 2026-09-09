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
        // libxposed api/service artifacts are published on Maven Central
        // (io.github.libxposed). No extra repo needed.
    }
}

rootProject.name = "Advanced Protection Bypasser"
include(":app")
// com.android.test module that generates the Baseline/Startup profiles.
include(":baselineprofile")
