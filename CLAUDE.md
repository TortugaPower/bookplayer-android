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

Three Gradle modules: **`:core`** (a Compose-free, playback-capable Android library holding the sharable
layers), **`:app`** (the phone app), and **`:wear`** (the Wear OS app). Both `:app` and `:wear` depend
on `:core`. `:wear` boots its own `WearApp` Application that wires `:core` (Room + network + RevenueCat)
the same way the phone does; its UI is tier-gated (PRO → standalone, else → phone remote) with the
per-mode experiences and the phone→watch sign-in handoff still landing in later slices.

```
core/                      # shared Android library — NO Compose, NO app types (Media3 IS allowed: playback lives here)
  src/main/java/com/tortugapower/audiobookplayer/
    database/dao|entities/ # Room (AppDatabase, DAOs, entities, Converters)
    network/               # Retrofit services / DTOs / NetworkClient / NetworkConstants
    model/                 # shared data models (SyncModels, ...)
    repository/            # data access, single source of truth per domain
    logic/                 # shared domain/sync logic: SyncTaskFactory + sync processors + engine
                           #   (CoreProcessors, TaskConcurrencyManager/Service), PlayableItem/
                           #   PlayableItemBuilder/BoundTimeline, chapter extraction, settings,
                           #   SubscriptionManager, StatisticsManager, PlaybackSyncCoordinator (iface),
                           #   PlaybackManager + SleepTimerManager (the shared Media3 player orchestration,
                           #   a MediaController client — the target injects its session service)
    service/               # abstract MediaPlaybackService (MediaLibraryService base: ExoPlayer build +
                           #   auth data source + BookTimelinePlayer + transport session callback) +
                           #   BookTimelinePlayer. Concrete registered services stay per-target.
    core/                  # CoreContext (app-context holder, set by the host at startup)
    datalayer/             # phone<->watch Wear Data Layer contract (WatchAuthPayload, WearDataLayer,
                           #   WatchAuthCodec — the pure, unit-tested reply codec shared by both sides)
  src/main/res/            # base + values-* for :core-OWNED strings only
app/                       # phone app — depends on :core
  src/main/java/com/tortugapower/audiobookplayer/
    ui/screens|components|theme/  # Compose screens, reusable Composables, Material3 theme
    viewmodel/             # ViewModels + their Factories
    service/               # AudioPlayerService (subclasses :core MediaPlaybackService; adds Android Auto browse +
                           #   MediaBrowseTree); sync foreground Service (TaskConcurrencyServiceHost)
    wear/                  # phone side of Wear: auth handoff (WearAuthListenerService) + remote-control
                           #   (WearRemotePublisher pushes state via DataClient; WearCommandListenerService
                           #   drives PlaybackManager from watch commands; WearStateBuilder/WearCommandMapper)
    widget/                # home-screen widget
    logic/                 # phone-only: ThemeManager, import, app-icon, tip/billing, support, passkey,
                           #   ShortcutHelper (pinned home-screen shortcuts; dynamic launcher shortcuts
                           #   live in MainActivity), PlaybackManagerSyncCoordinator (PlaybackManager/
                           #   SleepTimerManager moved to :core)
    model/                 # Media3 glue (Extensions.kt)
  src/main/res/            # values/ + 10 localized values-* dirs (ar, de, es, fr, hi, it, ja, ko, ru, zh-rCN)
wear/                      # Wear OS app — depends on :core; shares :app's applicationId (pairing), minSdk 30
  src/main/java/com/tortugapower/audiobookplayer/wear/
    WearApp.kt             # Application: wires :core (CoreContext/NetworkConstants/SubscriptionManager)
    auth/                  # WearAuthClient: requests the sign-in handoff from the phone (Data Layer)
    data/                  # remote-control transport: RemoteContextRepository (observe phone state via
                           #   DataClient), WearRemoteClient (send commands), shared WearableExt helpers
    presentation/          # Wear Compose UI (MainActivity, WatchMode gate, WearRootViewModel; RemoteViewModel
                           #   + RemoteNavHost: RemoteList/NowPlaying/PlaybackControls/ChapterList screens)
```

## Module conventions (`:core` / `:app`)

- **`:core` never references `:app`.** No Compose, `ui`/`widget`, `BookPlayerApplication`, or the
  app's `BuildConfig`/`R`. **Media3 IS allowed in `:core`** — `PlaybackManager` (the shared player
  orchestration, a `MediaController` client) lives here so phone + Wear reuse one codebase. `:core` also
  holds an **abstract `MediaPlaybackService`** (a `MediaLibraryService` base that builds the ExoPlayer +
  auth data source + `BookTimelinePlayer` + LoudnessEnhancer + the transport-only session callback) so
  both targets reuse one player-construction codebase. Only the **concrete, manifest-registered** service
  (phone: `AudioPlayerService` adds the Auto browse tree + notification `PendingIntent`; Wear: its own)
  stays **per-target** in `:app`/`:wear`, as does the foreground sync `Service` and the widget. The target
  injects its session-service `ComponentName` + hooks into `PlaybackManager.initialize`.
- **Config/Context crossing the boundary is injected, not read.** Flavored `BuildConfig` values
  (`BASE_URL`, `GOOGLE_CLIENT_ID`, `REVENUECAT_API_KEY`) are passed into `:core` at startup
  (`NetworkConstants.configure(...)`, `SubscriptionManager.initialize(..., apiKey)`); the app Context via
  `CoreContext.init(this)` in `Application.onCreate`. Never read `BuildConfig`/`R`/`BookPlayerApplication`
  from inside `:core`.
- **What `:core` needs from the target's player, it defines as an interface** and the target injects
  (e.g. `PlaybackSyncCoordinator`, implemented by `:app`'s `PlaybackManagerSyncCoordinator`).
- **`api` vs `implementation`:** a dependency whose types appear in `:core`'s **public API** is `api(...)`
  (Room, RevenueCat); otherwise `implementation(...)`. A module that uses a dependency **directly**
  declares it itself rather than relying on transitive exposure.
- **Moves preserve Kotlin package names** (zero import churn). Note Kotlin can't smart-cast a `var`
  property across module boundaries — capture to a local `val` first.
- **Each module has its own `.gitignore`** (`/build`). A `:core`-owned string keeps its base **and all**
  `values-*` translations in `:core`.
- **Tests live in the owning module.** `:core` Room/SQL tests run under Robolectric.

## Build / flavors / secrets

- **Flavors (`env` dimension):** `dev` (defaults `BASE_URL` to emulator loopback `http://10.0.2.2:5003`,
  so `devDebug` builds and unit tests run with **no secrets**) and `prod`.
- **Secrets** (`GOOGLE_CLIENT_ID`, `SENTRY_DSN`, `REVENUECAT_API_KEY`, `*_BASE_URL`) are read from a
  gitignored `local.properties` or env vars into `BuildConfig` — **never hardcode them in source**.
- **Sentry reporting** is on for `prod` builds only; a `dev` build reports only with
  `SENTRY_DEV_REPORTING=true` in `local.properties` (keeps emulator reproductions out of the issue list).
- **Release signing** comes from a gitignored `keystore.properties`; absent it, release builds unsigned.
- **Release R8 config** (`app`/`wear` `proguard-rules.pro` + `gradle.properties`): full mode, optimized
  resource shrinking and `-repackageclasses`. Anything another process resolves **by class name**
  (manifest components, Room `_Impl`, the Wear ongoing-activity surface) needs a keep rule AND an
  entry in `scripts/audit-mapping.sh`, which fails CI when such a class is renamed or moved.
- **CI** (`.github/workflows/ci.yml`): `assembleDevDebug`, `testDevDebugUnitTest`, `lintDevDebug`, then an
  unsigned minified `assembleProdRelease` (app + wear) and the mapping audit, on JDK 17.

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

- Branch model (mirrors the iOS repo): **`main` is what's live on the Play Store**; **`develop` is
  the staging branch for the next release** — feature/fix PRs target `develop`, and a release is a
  PR from `develop` into `main` (then tag + bundles from `main`).
