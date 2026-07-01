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

// The publishable SDKs.
include(":polymessaging")
// Voice calling (WebRTC) — separate artifact so chat-only consumers stay lean.
include(":polyvoice")

// Voice ("tap to call") demo, uses :polyvoice — {compose, views}.
include(":examples:voice:compose")
include(":examples:voice:views")

// Chat example ladder: 7 rungs x {compose, views} = 14 modules.
include(":examples:chat:compose:01-hello")
include(":examples:chat:compose:02-standard")
include(":examples:chat:compose:03-richcontent")
include(":examples:chat:compose:04-resilience")
include(":examples:chat:compose:05-handoff")
include(":examples:chat:compose:06-fullreference")
include(":examples:chat:compose:07-playground")
include(":examples:chat:views:01-hello")
include(":examples:chat:views:02-standard")
include(":examples:chat:views:03-richcontent")
include(":examples:chat:views:04-resilience")
include(":examples:chat:views:05-handoff")
include(":examples:chat:views:06-fullreference")
include(":examples:chat:views:07-playground")
