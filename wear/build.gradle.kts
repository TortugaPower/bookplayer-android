import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localProp(key: String, default: String = ""): String =
    (localProperties.getProperty(key) ?: System.getenv(key) ?: default).trim()

android {
    namespace = "com.tortugapower.audiobookplayer.wear"
    compileSdk = 35

    defaultConfig {
        // Shared with :app on purpose — the Play Store pairs the phone + watch apps by applicationId,
        // and the Wear Data Layer only connects nodes that share it (+ signing key).
        applicationId = "com.tortugapower.audiobookplayer"
        // Wear OS 3+ (API 30). The phone app goes back to 28, but there is no Wear OS below 30.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "REVENUECAT_API_KEY", "\"${localProp("REVENUECAT_API_KEY")}\"")
    }

    // Mirror :app's env flavor so dev builds default BASE_URL to the emulator loopback (no secrets
    // needed for devDebug/CI) and prod reads the real URL. Also means the top-level assembleDevDebug/
    // testDevDebugUnitTest/lintDevDebug CI tasks build + test :wear alongside :app.
    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            buildConfigField("String", "BASE_URL", "\"${localProp("DEV_BASE_URL", "http://10.0.2.2:5003")}\"")
        }
        create("prod") {
            dimension = "env"
            buildConfigField("String", "BASE_URL", "\"${localProp("PROD_BASE_URL")}\"")
        }
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
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Shared logic/data/network/subscription layer — the whole point of the module split.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // ProcessLifecycleOwner: gate the sync foreground service on process-foreground state.
    implementation(libs.androidx.lifecycle.process)

    // Media3 — the Wear playback service subclasses :core's MediaPlaybackService (ExoPlayer + MediaSession).
    // Types come transitively via :core (api), but declared here per the module convention (a module that
    // uses a dependency directly declares it). No media3-ui (that's phone-Compose only).
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    // Compose layout primitives (Box/Column/…) + foundation (basicMarquee) used directly by the UI —
    // declared explicitly per CLAUDE.md's module convention rather than leaned on transitively.
    implementation(libs.androidx.foundation.layout)
    implementation(libs.androidx.foundation)
    // Wear-specific Compose (androidx.wear.compose.material), NOT the phone's material3.
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    // Wear Data Layer — auth handoff (WearAuthClient) + remote-control state/commands (RemoteContextRepository,
    // WearRemoteClient).
    implementation(libs.play.services.wearable)

    // Wear Tile (glanceable now-playing/resume): TileService + ProtoLayout (+ Material components). The
    // coroutines-guava bridge turns onTileRequest's suspend data fetch into the ListenableFuture it returns.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material)
    implementation(libs.kotlinx.coroutines.guava)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
