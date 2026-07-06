plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tortugapower.audiobookplayer.wear"
    compileSdk = 35

    defaultConfig {
        // Shared with :app on purpose — the Play Store pairs the phone + watch apps by applicationId,
        // and the Wear Data Layer only connects nodes that share it (+ signing key). Sign-in handoff
        // in a later slice relies on this.
        applicationId = "com.tortugapower.audiobookplayer"
        // Wear OS 3+ (API 30). The phone app goes back to 28, but there is no Wear OS below 30.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    // Wear-specific Compose (androidx.wear.compose.material), NOT the phone's material3.
    // compose-foundation comes in transitively; it'll be declared directly once a later slice's UI
    // uses it (ScalingLazyColumn, curved text, etc.) — kept off the graph for now to avoid a dead dep.
    implementation(libs.androidx.wear.compose.material)

    debugImplementation(libs.androidx.ui.tooling)
}
