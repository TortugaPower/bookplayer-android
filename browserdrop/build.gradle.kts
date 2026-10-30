// Upload-only HTTP server for "Transfer from Computer": the Android port of SwiftBrowserDrop
// (github.com/TortugaPower/SwiftBrowserDrop). Standalone on purpose: it depends on nothing of ours, so the
// app owns the sheet and the import handoff, and the module can be moved to its own repo unchanged.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tortugapower.browserdrop"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // `api`: the server's public surface is StateFlow/Flow and suspend functions.
    api(libs.kotlinx.coroutines.android)
    // @ColorInt on PageSettings.accentColor.
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
