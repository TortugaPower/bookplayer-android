# BookPlayer (Android)

Open-source audiobook player. Native Android app, Kotlin + Jetpack Compose. Companion to the
iOS BookPlayer app and shares the same BookPlayer backend (sync, auth, subscriptions).

## Tech stack

- **Language / build:** Kotlin, Gradle (Kotlin DSL), KSP. JDK 17. `minSdk 28`, `targetSdk`/`compileSdk 35`.
- **UI:** Jetpack Compose + **Material3**, Navigation Compose, Coil (images), `konfetti` (effects).
- **Architecture:** MVVM. `ViewModel` + `StateFlow` for state; UI observes and renders.
- **DI:** **Manual** — `ViewModelProvider.Factory` per ViewModel (e.g. `LibraryViewModelFactory`),
  dependencies constructed in `BookPlayerApplication`. No Hilt/Dagger/Koin. Keep it this way unless
  a change is explicitly about introducing DI.
- **Persistence:** Room (`database/dao`, `database/entities`) compiled via KSP; DataStore Preferences for settings.
- **Networking:** Retrofit + Gson (`network/`), talking to the BookPlayer API.
- **Audio:** AndroidX **Media3** — `ExoPlayer` + `MediaSession` + Media3 UI. This is the core of the app.
- **Background work:** WorkManager.
- **Auth:** AndroidX Credentials API + Google ID (Sign in with Google).
- **Monetization:** Google Play Billing + **RevenueCat** (`purchases`). `pro` is currently the only
  subscription flow; a `lite` tier (Jellyfin / AudiobookShelf integrations) is planned but not built.
- **Observability:** Sentry.

## Project layout

```
app/                       # main application module
  src/main/java/com/tortugapower/audiobookplayer/
    ui/screens/            # Compose screens (library, player, profile, synctasks, ...)
    ui/components/         # reusable Composables
    ui/theme/              # Material3 theme, colors, typography
    viewmodel/             # ViewModels + their Factories
    repository/            # data access, single source of truth per domain
    network/               # Retrofit services / DTOs
    database/dao|entities/ # Room
    logic/                 # domain logic / use cases
    service/               # foreground / playback service
    model/                 # shared models
  src/main/res/            # values/ + 10 localized values-* dirs (ar, de, es, fr, hi, it, ja, ko, ru, zh-rCN)
databasemodels/            # shared DB model definitions
```

## Build / flavors / secrets

- **Flavors (`env` dimension):** `dev` (defaults `BASE_URL` to emulator loopback `http://10.0.2.2:5003`,
  so `devDebug` builds and unit tests run with **no secrets**) and `prod`.
- **Secrets** (`GOOGLE_CLIENT_ID`, `SENTRY_DSN`, `REVENUECAT_API_KEY`, `*_BASE_URL`) are read from a
  gitignored `local.properties` or env vars into `BuildConfig` — **never hardcode them in source**.
- **Release signing** comes from a gitignored `keystore.properties`; absent it, release builds unsigned.
- **CI** (`.github/workflows/ci.yml`): `assembleDevDebug`, `testDevDebugUnitTest`, `lintDevDebug` on JDK 17.

## Conventions

- **Prefer native Android / Compose / Material3 APIs** over hand-rolled implementations.
- ViewModels expose immutable `StateFlow`; never leak `MutableStateFlow` or hold `Context`/`View`/`Activity`.
- Run IO (network, DB, disk) off the main thread (`Dispatchers.IO`), launched from `viewModelScope` —
  never `GlobalScope`.
- **Localization:** all user-facing text goes through `stringResource` / `strings.xml`. Adding a new
  string means adding the `values/strings.xml` key; the localized `values-*` files are translated separately.
- **Accessibility is a first-class concern** (this is an audiobook app with many low-vision users):
  every interactive / icon-only control needs a meaningful `contentDescription` (or `semantics {}`),
  sourced from `stringResource`, **not** a hardcoded English literal. Decorative-only icons use
  `contentDescription = null` deliberately.
- Media3 `ExoPlayer` / `MediaSession` must be released on the appropriate lifecycle; the playback
  service must be started/stopped correctly to avoid leaks and stuck foreground notifications.
- New repository / `logic` behavior should come with a unit test.

## Git

- Default branch: `main`.
