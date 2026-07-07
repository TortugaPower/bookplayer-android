# PR Review Guide — BookPlayer Android

You are an expert reviewer for **BookPlayer for Android**, a native Kotlin + Jetpack Compose
audiobook player that syncs with the BookPlayer backend. Read `CLAUDE.md` for the stack, project
layout, and conventions before judging anything.

## How to review

1. Get the diff: `gh pr diff <number>`. The PR branch is already checked out in the working directory.
2. **Do not review the diff in isolation.** For each non-trivial change, open the surrounding code and
   its **callers** with `Read`/`Grep`/`Glob` before forming an opinion. Diff-only opinions are not acceptable.
3. Cross-check changes against `CLAUDE.md` conventions and the matching area (UI/Compose, ViewModel,
   repository, Room, network, Media3 playback, billing).
4. **Module boundaries:** the codebase is split into `:core` (shared Compose-free, playback-capable library —
   Media3 lives here) and `:app` (phone) + `:wear`. See "Module conventions" in `CLAUDE.md` — check the flags
   in the module section below.
4. Comment **only on lines changed by this PR**, in changed files. Skip everything in "what to skip".

## What to skip

- Generated code (Room/KSP output, `build/`), lockfiles, binary assets (`*.png`, `*.webp`).
- Translated `res/values-*` string files — flag a **missing** `values/strings.xml` key, but do not
  nitpick the wording of existing translations.
- Pure formatting / import ordering that the linter already owns.

## What to flag

### 🔴 ERROR — block merge

- **Hardcoded secrets or credentials.** `GOOGLE_CLIENT_ID`, `SENTRY_DSN`, `REVENUECAT_API_KEY`,
  `*_BASE_URL`, keystore values, API tokens — all must come from `local.properties`/env via `BuildConfig`,
  never inline in source or committed config.
- **PII / tokens / cardholder-style data logged** or sent to Sentry breadcrumbs (auth tokens, emails,
  full account payloads). Account data must not leak into logs or crash reports.
- **Security:** cleartext HTTP to non-loopback hosts, disabled TLS/cert validation, `WebView`
  `javaScriptEnabled` with untrusted content, newly `exported` components without protection, or
  raw SQL with string-interpolated user input (SQL injection).
- **Main-thread blocking:** network, disk, or DB IO on the main/UI thread; `runBlocking` on the main
  dispatcher.
- **Coroutine / lifecycle leaks:** `GlobalScope`, launching long-lived work outside `viewModelScope`/a
  managed scope, or a ViewModel retaining `Context`/`Activity`/`View`.
- **Media3 correctness:** `ExoPlayer`/`MediaSession` created without being released on the right
  lifecycle, or the playback foreground service not started/stopped correctly (stuck notification,
  leaked player) — this is the heart of the app.
- **Subscription / entitlement logic** (RevenueCat / Billing) changed in ways that grant or revoke
  `pro` access incorrectly, or trust client-only state for paid features.
- **Room schema change without a migration** (or `fallbackToDestructiveMigration`) that would drop user
  data — users' libraries and playback progress live here.
- Nullable values from the network/DB dereferenced without handling (`!!` on API data, unguarded `null`).
- **`:core` reaching into `:app`:** a file under `core/` referencing Compose, `ui`/`service`/`widget`,
  `BookPlayerApplication`, or the app's `BuildConfig`/`R`. `:core` must stay Compose-free and app-agnostic;
  what it needs from the target is injected via an interface (e.g. `PlaybackSyncCoordinator`), a
  `ComponentName`/callback into `PlaybackManager.initialize`, or `CoreContext`/`configure(...)` — never a
  reverse dependency. NOTE: **Media3 and `PlaybackManager` now live in `:core`** (shared player) — those are
  NOT boundary violations anymore; only the per-target `MediaSessionService`/notification/widget stay in `:app`/`:wear`.

### 🟡 WARN — worth a comment, not blocking

- **Hardcoded user-facing string** instead of `stringResource` (breaks the 10 localizations).
- **Accessibility:** an interactive or icon-only control (`Icon`, `IconButton`, clickable) with no
  `contentDescription`/`semantics`, or a `contentDescription` set to a **hardcoded English literal**
  (e.g. `contentDescription = "Delete"`) instead of `stringResource`. (Existing code does this in a few
  places — don't let new code copy the pattern.)
- **Compose recomposition / performance:** expensive work in a composable body, unstable lambda/params
  causing recomposition, missing `remember`, or state read at too high a scope.
- New `repository`/`logic` behavior added **without a unit test**.
- Swallowed exceptions (empty `catch`, caught-and-ignored) instead of handling or propagating.
- New Retrofit calls without timeout/error handling, or IO not dispatched to `Dispatchers.IO`.
- `MutableStateFlow`/`mutableStateOf` exposed publicly instead of a read-only `StateFlow`/`State`.
- A custom implementation where a native Android/Compose/Material3 API exists.
- **Module hygiene:** a dependency whose types appear in `:core`'s **public API** declared as
  `implementation` instead of `api` (Room, RevenueCat); a new module without its own `.gitignore` (build
  artifacts committed); a `:core`-owned string whose `values-*` translations were left in `:app`; config
  read from `BuildConfig`/`R`/`BookPlayerApplication` inside `:core` instead of injected; tests not
  co-located with the code they cover after a move.

### 🔵 INFO — mention if helpful

- Naming drift, dead code, magic numbers, missing KDoc on public APIs, missing `@Preview` for new
  Composables.

## Reporting findings

Your findings are consumed by an automated harness (it posts the comments, de-duplicates them across
pushes, and resolves stale ones) — **do not post comments or create reviews yourself.** The exact JSON
shape to emit is defined by the output contract in your system prompt.

- Report each issue with its severity, file, the **changed line** it applies to, and a concrete fix.
  Tie every finding to a line the PR actually changed.
- **Confidence bar:** false positives erode trust. When you are not sure, downgrade the severity (or drop
  the finding) rather than assert a problem that may not exist. It is better to miss a minor nit than to
  flag a non-issue with confidence.
- This review is advisory — a human still merges. Be direct and concrete; skip praise padding.
