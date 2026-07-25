plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.tortugapower.audiobookplayer.core"
    compileSdk = 35

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

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric tests that resolve real resources (e.g. the :core-owned container-count
        // plurals in LibraryContentsSyncTest.displayDetails) need them packaged for unit tests.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Persistence (Room) lives here so :app and :wear share one DB layer. Exposed as `api` because
    // consumers touch `AppDatabase` (whose supertype `RoomDatabase` must be on their classpath).
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.gson)

    // Networking (Retrofit/OkHttp) — the shared API layer. Consumers that touch retrofit2.Response
    // (e.g. app's CoreProcessors) declare retrofit themselves, so these stay `implementation`.
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    // Sentry OkHttp integration — captures failed backend responses (5xx) as Sentry
    // error events. Attached ONLY to the shared backend client in NetworkClient;
    // third-party (Jellyfin/ABS/Hardcover/GitHub) and S3 transfer clients are left
    // uninstrumented so we only report on our own backend. On :wear (which consumes
    // :core but deliberately never initializes Sentry) the interceptor binds to
    // NoOpHub and is inert — wear backend 5xx are NOT reported.
    implementation(libs.sentry.okhttp)

    // DataStore-backed settings that moved into :core (e.g. HardcoverSettingsManager).
    implementation(libs.androidx.datastore.preferences)

    // RevenueCat — SubscriptionManager (tier gate) lives in :core, shared with Wear. `api` because its
    // public methods (e.g. updateAccountTier(CustomerInfo)) expose RevenueCat types to consumers.
    api(libs.purchases)

    // Media3 — PlaybackManager (the shared player orchestration) lives in :core so phone AND Wear reuse one
    // codebase (Media3/ExoPlayer/audio-focus are identical on both, unlike iOS which had to fork). `api`
    // because PlaybackManager's public surface exposes Media3 types (Player, MediaController, MediaItem).
    // NOTE: this deliberately makes :core playback-capable — it is no longer Media3-free (see CLAUDE.md).
    api(libs.androidx.media3.session)
    api(libs.androidx.media3.common)
    // Media3 ExoPlayer — the shared MediaPlaybackService base (the abstract MediaLibraryService that
    // builds the ExoPlayer + auth data source + BookTimelinePlayer) lives in :core so phone AND Wear
    // reuse one player-construction codebase; each target only subclasses it to add its own session
    // Activity, browse tree, and notification button row. `api` because the base's protected surface
    // exposes ExoPlayer (subclasses touch it). Pulls in media3-datasource transitively (DefaultDataSource,
    // DefaultHttpDataSource, DataSourceBitmapLoader).
    api(libs.androidx.media3.exoplayer)

    // androidx.core — MediaPlaybackService uses IntentCompat.getParcelableExtra (the type-safe overload
    // needs core 1.10+, newer than what Room/DataStore pull transitively). Internal use only → implementation.
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver) // HttpRangeByteSourceTest
    testImplementation(libs.robolectric)          // in-memory Room DAO tests (JVM)
    testImplementation(libs.androidx.test.core)
}
