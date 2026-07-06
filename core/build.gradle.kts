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

    // DataStore-backed settings that moved into :core (e.g. HardcoverSettingsManager).
    implementation(libs.androidx.datastore.preferences)
}
