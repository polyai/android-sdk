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

// Auto-provision the JDK 17 toolchain (the build pins jvmToolchain(17)) so it works in
// Android Studio (which runs Gradle on JDK 21), on CI, and from a plain terminal —
// without anyone hand-installing a JDK 17. Downloads it on first sync.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "polymessaging-android"

// The publishable SDK.
include(":polymessaging")

// Example ladder: 7 rungs x {compose, views} = 14 modules.
// Uncomment each as it is implemented (M4/M5). Kept here so the layout is visible from day one.
include(":examples:compose:01-hello")
include(":examples:compose:02-standard")
include(":examples:compose:03-richcontent")
include(":examples:compose:04-resilience")
include(":examples:compose:05-handoff")
include(":examples:compose:06-fullreference")
include(":examples:compose:07-playground")
include(":examples:views:01-hello")
include(":examples:views:02-standard")
include(":examples:views:03-richcontent")
include(":examples:views:04-resilience")
include(":examples:views:05-handoff")
include(":examples:views:06-fullreference")
include(":examples:views:07-playground")
