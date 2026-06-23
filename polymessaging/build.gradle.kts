// Copyright PolyAI Limited

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

android {
    namespace = "ai.poly.messaging"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // Ship R8 keep rules to consumers; the app's R8 pass applies them (lib must not self-minify).
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Single source for the SDK version -> User-Agent.
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

// Maven Central (Sonatype Central Portal) publishing. Host + POM metadata come from gradle.properties
// (SONATYPE_HOST=CENTRAL_PORTAL, POM_*). GPG signing is a HARD Central requirement and is NOT automatic
// in this plugin — without signAllPublications() the release uploads unsigned artifacts and Central
// rejects the whole deployment. Guard it on the signing key being present so local publishToMavenLocal
// (used by the consumer smoke-test) still works without a key; CI supplies the key via
// ORG_GRADLE_PROJECT_signingInMemoryKey (see .github/workflows/release.yml).
mavenPublishing {
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
}

// Interim distribution channel: GitHub Packages (the private repo's own Maven registry). Lets internal
// consumers pull the SDK before the public Maven Central release is live (DNS namespace verification
// pending). No GPG/namespace needed; CI authenticates with the automatic GITHUB_TOKEN. The public
// install path remains Maven Central (vanniktech config above) once ai.poly is verified.
publishing {
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
    // Flow/coroutines is the public streaming surface -> api.
    api(libs.kotlinx.coroutines.core)
    // Everything else is hidden from consumers (no leaked types) -> implementation.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit)
    testImplementation(kotlin("test")) // kotlin.test assertions on JUnit4
    testImplementation("org.json:json:20231013") // real org.json so wire/JWT tests run on the JVM (the android.jar one is a stub)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.uiautomator)
}
