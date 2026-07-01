// Copyright PolyAI Limited

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.publish)
    // NOTE: no Dokka here (unlike :polymessaging) — see the mavenPublishing block below for why the
    // javadoc jar is empty for this module.
}

android {
    namespace = "ai.poly.voice"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // Ship R8 keep rules to consumers; the app's R8 pass applies them (lib must not self-minify).
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Single source for the SDK version -> User-Agent on the voice REST calls.
        buildConfigField("String", "VERSION_NAME", "\"${providers.gradleProperty("VERSION_NAME").get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true // Robolectric
        }
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi() // SDK discipline: every public declaration must state its visibility.
}

// Maven Central (Sonatype Central Portal) publishing. Mirrors :polymessaging — host + shared POM
// metadata come from the root gradle.properties; this module's POM_ARTIFACT_ID/POM_NAME are
// overridden in polyvoice/gradle.properties so it publishes as ai.poly:voice. GPG signing is a HARD
// Central requirement, guarded on the key being present so local publishToMavenLocal still works.
mavenPublishing {
    // Publish the `release` variant + sources, but NOT AGP's javadoc jar. AGP's javaDocReleaseGeneration
    // runs Dokka, whose K1 ASM can't read this module's cross-module public signatures
    // (StateFlow<CallState>, PolyError — Java-17 *sealed* classes in :polymessaging) and dies with
    // "PermittedSubclasses requires ASM9". We attach our own empty javadoc jar below instead — Maven
    // Central only requires the jar to EXIST. The API is documented in source KDoc + the README.
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = false))

    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
}

// Central-valid empty javadoc jar in place of AGP's Dokka task (see mavenPublishing above). Added
// lazily via configureEach so it lands on vanniktech's publication BEFORE the Sign task runs and is
// therefore signed alongside the other artifacts.
val emptyJavadocJar = tasks.register<Jar>("emptyJavadocJar") {
    archiveClassifier.set("javadoc")
}

// Interim distribution channel: GitHub Packages (matches :polymessaging).
publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(emptyJavadocJar)
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/polyai/android-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

dependencies {
    // Reuse the messaging SDK's public types (Configuration, Environment, PolyError, CallState,
    // Callback, Cancellable, PolyLogger). `api` so consumers see them without a second import.
    api(project(":polymessaging"))
    // Flow/coroutines is the public streaming surface -> api.
    api(libs.kotlinx.coroutines.core)
    // Everything below is hidden from consumers (no leaked types) -> implementation.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // libwebrtc native engine. Kept `implementation` — no org.webrtc type appears in the public API.
    implementation(libs.webrtc.sdk)

    testImplementation(libs.junit)
    testImplementation(kotlin("test")) // kotlin.test assertions on JUnit4
    testImplementation("org.json:json:20231013") // real org.json so protocol tests run on the JVM (the android.jar one is a stub)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
}
