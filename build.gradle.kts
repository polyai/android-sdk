// Copyright PolyAI Limited

// Root build script. Plugins are declared here with `apply false` and applied per-module,
// providing a single shared configuration surface.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.dokka) apply false
    // Public-API surface guard (apiDump / apiCheck) — applied at the root, validates :polymessaging.
    alias(libs.plugins.bcv)
}

// binary-compatibility-validator: only the published SDKs have a tracked ABI; ignore samples/tests.
apiValidation {
    // The published SDKs (:polymessaging, :polyvoice) have a tracked public ABI; ignore the
    // example apps + container projects.
    val tracked = setOf("polymessaging", "polyvoice")
    ignoredProjects.addAll(subprojects.map { it.name }.filter { it !in tracked })
}

