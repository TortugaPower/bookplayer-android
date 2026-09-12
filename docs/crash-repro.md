# Reproducing production crashes on the emulator

Every crash fix in this repo ships with two things: a unit test that reproduces the failing
condition, and a scripted emulator scenario under `scripts/chaos/` that demonstrates the crash on
the build before the fix and its absence after. The emulator proves the mechanism; the Sentry
per-release event breakdown a week after rollout proves the fleet. Both are required before an
issue is marked "resolved in release" in Sentry.

## Emulators

`scripts/chaos/create-avds.sh` creates the two AVDs the recipes assume (downloads the system
images on first run):

| AVD | Image | Why |
|---|---|---|
| `bp-lowend-31` | API 31 google_apis, Pixel 3a profile, 2 GB RAM, 2 cores | The low-end Android 12 phones that dominate the crash list. Per-app heap growth limit boots at 192 MB. `google_apis` (not Play) so `adb root` works. |
| `bp-api36` | API 36 google_apis, Pixel 7 profile | Android 15+/16 foreground-service rules: the dataSync 6 h budget, `onTimeout`, promotion deadlines. |

Boot: `$ANDROID_HOME/emulator/emulator -avd bp-lowend-31 -no-snapshot-load -no-boot-anim -no-audio`.
Install the build under test with `adb install -r -g app/build/outputs/apk/dev/debug/app-dev-debug.apk`
(the `dev` flavor needs no secrets and is debuggable, which `run-as` in the scripts requires).

## Recipes

### ANDROID-BOOKPLAYER-13 — DataStore corruption crash loop

`scripts/chaos/corrupt-playback-settings.sh`

Zero-fills `files/datastore/playback_settings.preferences_pb` and launches the app twice.

* Before: both launches die with `CorruptionException: Unable to parse preferences proto`
  (`InvalidProtocolBufferException: Protocol message contained an invalid tag (zero)`).
* After: the app starts, logs `W/PlaybackSettings: playback_settings is corrupt; resetting to defaults`,
  and rewrites the file. Unit test: `PlaybackSettingsCorruptionTest`.

### ANDROID-BOOKPLAYER-17 / -18 — OOM importing a file with a huge embedded cover

```
scripts/chaos/make-huge-cover-m4b.sh core/src/test/resources/chapterfixtures/m4b_MALFORMED.m4b /tmp/cover.m4b 60
scripts/chaos/heap-limit.sh 64m
adb shell pm clear com.tortugapower.audiobookplayer      # a same-named file is otherwise deduplicated
scripts/chaos/import-file.sh /tmp/cover.m4b
```

Cover art lives inside `moov/udta`, so a 60 MB cover makes a 60 MB `moov` (the Sentry files had
a 29 MB one). Import used to make **two** allocations the size of the cover, one line apart in
`ImportManager.createBookItem`:

1. `ArtworkManager.extractAndSaveArtwork` — `MediaMetadataRetriever.embeddedPicture` returns the
   whole picture as one array.
2. `AudioChapterExtractor` — read the whole `moov` into one array to find the chapter track.

Either one fails on a nearly full heap (the crashing phones had 10–13 MB of headroom at a 256 MB
limit; Sentry caught the second because production heaps happened to get past the first). The
`dev` build's live heap on a cleared install is only ~15 MB, so the limit has to come down to 64 MB
before a 60 MB allocation fails; the app still starts fine there.

* Before (verified 2026-09-02): `OutOfMemoryError: Failed to allocate a 62664136 byte allocation ...
  growth limit 67108864` at `ArtworkManager.extractAndSaveArtwork(ArtworkManager.kt:29)`; with the
  extractor alone fixed, the same file died there. With a `free`-atom `moov` instead
  (`make-huge-moov-m4b.sh`, no cover), the pre-fix build died in `FileByteSource.readAt` ←
  `AudioChapterExtractor.readTopLevelBox` ← `ChapterExtractionService` ← `createBookItem` — the
  production stack. Process dies, no item created.
* After: both files import at the 64 MB limit — 1 item, the fixture's 4 chapters, and a 49 KB
  artwork JPEG downsampled straight from the 60 MB PNG. No single read exceeds a few KB.
  Unit tests: `AudioChapterExtractorMemoryTest`, `EmbeddedCoverLocatorTest` (sources that refuse reads
  over 8 MB), `ArtworkManagerTest` (a real tiny PNG comes out as a JPEG).

How: `ContainerReaders.kt` walks MP4 boxes / ID3 frames as byte *ranges*; the extractor
materializes only the chapter `trak`, and `EmbeddedCoverLocator` hands `ArtworkManager` the
`covr`/`APIC` range, which is decoded through a stream with `inSampleSize`. Containers the locator
does not parse (FLAC, OGG…) still go through `MediaMetadataRetriever`, with an `OutOfMemoryError`
guard so a giant picture costs the cover, not the import. The `:app` `CoverArtResolver` (covers
resolved lazily for the library list, Android Auto and its remote prefetch) goes through the same
`ArtworkManager.saveEmbeddedArtwork`, keeping its "no art" negative cache distinct from transient failures.

Notes on the import flow, learned the hard way: an `ACTION_VIEW file://` intent only stages the
file and opens the import sheet; the item is created after **Accept** and then choosing
**Library** in the "Import Complete" dialog. `import-file.sh` drives both through the accessibility
tree. Files pushed to `/sdcard/Android/data/<pkg>` after `adb root` are unreadable by the app
(`EACCES`); the script copies into the app's private files dir instead.

### ANDROID-BOOKPLAYER-19 / -15 — FOREIGN KEY failures writing child rows for a vanished book

Both `playback_sessions.bookUuid` (-19, `StatisticsDao.insertSession`) and `chapters.bookUuid`
(-15, `LibraryDao.replaceChaptersForBook` from the play-time chapter extraction) reference
`library_items.uuid`. Both writers run asynchronously after a play/load, so the book can be gone by
the time they land: deleted by the user, or replaced by a sync pull (uuid churn). The failed insert
threw out of a coroutine with no handler and took the process down.

Reproduction is unit-level (the race is a timing window, not a UI path):
`LibraryDaoTest.replaceChaptersForBook_skipsWhenTheBookIsGone` (in-memory Room, real FK) and
`StatisticsManagerTest.testStartSession_bookNoLongerInLibrary_recordsNothing` /
`testDaoFailure_isLoggedNotThrown_andLaterEventsStillWork`.

Fix: both DAO writes check the parent row **inside the same transaction** and write nothing when it is
gone (`StatisticsDao.startSession` returns null, `replaceChaptersForBook` returns). Statistics
coroutines additionally run under a `CoroutineExceptionHandler` — bookkeeping must never take
playback down, whatever the DB throws (this also covers a full disk on the heartbeat write).

### ANDROID-BOOKPLAYER-1A — "Session ID must be unique" creating the playback service

media3 keeps session ids in a process-wide registry and refuses a duplicate; the service used the
default `""` id. A session registered but never released in the same process — a `build()` that
failed after registering, an OEM retrying service creation without killing the process (both
reports are OPPO / ColorOS) — made every later service creation die at `MediaLibrarySession.Builder.build()`.
Not reproducible on a stock emulator (a failed `onCreate` kills the process there, which also clears
the registry). Fix: each instance takes a fresh `bookplayer-<n>` id (controllers connect through the
ComponentName, never the id), and `onDestroy` releases the session even if the player's release throws.
Smoke: start playback and check `adb shell dumpsys media_session | grep bookplayer-`.

### ANDROID-BOOKPLAYER-Q — media3 `IllegalStateException` in `MediaUtils.mergePlayerInfo`

The app's own `MediaController` merged a player update against a stale timeline whose window count
was smaller than the new item index (`PlayerInfo.Builder.build` assertion). It is a session ↔
controller race inside media3, not something our wrapper reports inconsistently: `BookTimelinePlayer`
derives every index from `BoundTimeline.chapterLocalOf`, whose clamping at both ends is pinned by
`BoundTimelineTest`. Fixed upstream in media3 1.11.0 ("Fix an out-of-bounds timeline merge crash by
tracking state consistency per-controller on the session side"), so the fix here is the dependency bump.

Not reproducible on demand (it needs several controllers and rapid timeline changes to line up), so
the evidence is the release note plus the fleet: -Q must stay quiet on the release that ships 1.11.0.

What the bump changed for us (1.7.1 → 1.11.0):
- `MediaNotification.Provider` gained a required `getNotificationChannelInfo()`; the Wear
  `OngoingMediaNotificationProvider` delegates it to the default provider.
- 1.10 stopped honouring device-volume commands from a `MediaController` for local playback — the
  Wear crown's path. Volume now goes through `AudioManager` (`DeviceVolume`, tested under Robolectric);
  ExoPlayer's `setDeviceVolumeControlEnabled` is gone with it.
- `MediaSession` getters now throw off the application looper (1.11); all our calls are on main.
- No notification for an idle player holding items (1.8) does not apply: every `setMediaItems` is
  followed by `prepare()`.

Smoke after the bump: phone playback, media notification, media-button seek/pause on the emulator;
**Wear crown volume + ongoing activity, and Android Auto, still need a manual pass** on real hardware.

### ANDROID-BOOKPLAYER-12 / -10 / -1D / -1G / -S / -V — device out of storage

Six groups, one condition. Two shapes: `SQLITE_IOERR_SHMSIZE` on `PRAGMA journal_mode` is the
database failing to *open* (SQLite can't size its WAL shared-memory file at zero bytes free — every
launch dies); `SQLiteFullException` / `ENOSPC` is a write failing while the app runs (a progress
tick, a settings save, a download). Storage most often fills *while* the app runs, so the guard has
two layers sharing one state, `StorageMonitor` (`:core`):

- **Measured**: free bytes on the app's data volume at launch, on resume, before any transfer of
  known size, and every 10 s while the state is short. Below 32 MB is *critical*.
- **Observed**: a write that failed for lack of space (recognised anywhere in the cause chain) flips
  the state to critical and stays sticky until a later measurement sees 64 MB free again.

What the state drives: `MainActivity` shows the storage screen instead of the app when critical at
launch (nothing touches the database); `MainScreen` shows a banner; the sync engine runs nothing
while critical and holds downloads while a transfer is known not to fit (`StoragePolicy`); downloads
and imports pre-flight their size against free space with a 64 MB reserve; **playback is refused, and
running playback is paused, while critical** — listening progress can't be saved, and losing the
user's place is not acceptable; a dialog explains. Every long-lived coroutine scope that writes
(`PlaybackManager`, the sync engine and host, statistics, settings, imports, Wear publishers,
widget, shortcuts) runs under `StorageMonitor.exceptionHandler`: a full-disk failure is recorded, any
other exception still crashes as before. The unused WorkManager dependency is gone — its auto-init
wrote to its own database at process start and was the first thing to die at zero bytes free.

Recipes (`scripts/chaos/fill-disk.sh`; note the root-vs-app free-space difference in its header):

```
# launch with no free space → storage screen, no exit; free space → "Check again" restarts the app
adb shell "run-as com.tortugapower.audiobookplayer sh -c 'rm -f databases/bookplayer.db-shm databases/bookplayer.db-wal'"
scripts/chaos/fill-disk.sh fill; <launch>; scripts/chaos/fill-disk.sh free
# free space runs out during playback → paused within one progress tick (≤10 s), dialog, media-key play refused;
# free space → banner clears within 10 s, play works again
<play a book>; scripts/chaos/fill-disk.sh fill; …; scripts/chaos/fill-disk.sh free
# an import that would leave less than the reserve is refused (stage the file, then leave ~70 MB app-visible)
scripts/chaos/fill-disk.sh fill 214000; <open the file>; scripts/chaos/fill-disk.sh free
```

Verified 2026-09-03 on `bp-lowend-31`: before, launch at zero bytes died (`SQLiteFullException` on
WorkManager's `WM.task-1`, then `SQLITE_IOERR_SHMSIZE` from the account flow); after, all three
recipes complete with no process exit. The recovery restart was also exercised under load ("Check
again" → `recreate()` → sync host start, with an import fired straight after): the host was created
200 ms after the tap and promoted at once. While gated, a handful of startup scopes still touch the
database once each and log `A write failed because storage is full` — that is the handler doing its
job, not a leak. Unit tests: `StorageMonitorTest` (classification of the
Sentry shapes, thresholds, hysteresis, the handler forwarding non-storage errors),
`StoragePolicyTest`. Not covered yet: writes launched from ViewModel scopes (rename, delete, bookmarks)
while the disk is full — the banner appears within one heartbeat, but a write racing it can still
throw; a repository-level guard is the follow-up.

### ANDROID-BOOKPLAYER-11 — Background ANR creating the LoudnessEnhancer

The main thread was parked in `LoudnessEnhancer.<init>` → `AudioFlinger::createEffect`, a synchronous
binder call into audioserver, called from ExoPlayer's `onAudioSessionIdChanged` listener (which runs on
the application looper). On a low-end Redmi (Android 14) that call stalled long enough for a Background
ANR. Fix: `LoudnessBooster` (`:core/service`) owns the effect on its own single-thread executor; the
service only posts attach / setEnabled / release commands, so a stalled audioserver stalls that thread
and nothing else. Commands apply in order (latest session wins, the boost setting is remembered across
re-attach, release drops later commands).

Not reproducible on the emulator — its audioserver never stalls. The contract is unit-tested instead
(`LoudnessBoosterTest`: a factory that blocks on a latch must not block `attach()`; ordering; failure;
release). Sanity check on a device or emulator: play, toggle *Volume boost* in settings a few times;
`adb shell dumpsys media.audio_flinger | grep -i loudness` shows the effect attached to the session.

### ANDROID-BOOKPLAYER-1E — "Bad notification for startForeground"

One TECNO (Android 12) report on 1.1.2; the breadcrumbs show only rapid background/foreground cycling.
Android 12+ dropped the cause from this message and the stack has no app frames, so the report cannot
say which service was promoting. Both promotion sites now leave an `fgs` breadcrumb
(`TaskConcurrencyServiceHost` before `startForeground`, `AudioPlayerService.onUpdateNotification` when
`startInForegroundRequired`). Nothing to fix until it recurs with a breadcrumb attached.

### ANDROID-BOOKPLAYER-1P / -1R — tapping a link on a device with no browser

`scripts/chaos/no-browser.sh`

Disables Chrome (`pm disable-user`), the only `https` handler on a google_apis image, launches the app
and taps Settings → "View project on GitHub". Compose's platform `UriHandler` rethrows the
`ActivityNotFoundException` from `startActivity` as an `IllegalArgumentException("Can't open …")`, and
nine call sites (Tip Jar contributors, Settings links, Account terms/privacy, Hardcover) used it bare.

* Before: `FATAL EXCEPTION: main … IllegalArgumentException: Can't open https://github.com/TortugaPower/bookplayer-android`
  and the process is gone (`pidof` empty). Same shape as the Sentry report, from a different link.
* After: the process is alive and `NotificationService` logs a toast from the package ("No app on this
  device can open links."; toast windows are not in the accessibility dump, so logcat is the check).
  `BookPlayerTheme` installs `SafeUriHandler` as `LocalUriHandler`, so every
  call site is covered without changing any of them. Unit test: `SafeUriHandlerTest`.
* Verified 2026-09-11 on `bp-lowend-31` (Pixel 3a profile, API 31 — the report was a Pixel 3 on 12).
  Re-enable Chrome afterwards: `adb shell pm enable com.android.chrome`.

### Not yet scripted

| Issue | Planned recipe |
|---|---|
| -1H / -X sync-host promotion timeout | `bp-lowend-31`, 500-item library, cold start; or a debug flag blocking the main thread 12 s after launch. The host now promotes before opening the database (hardening, not a verified fix). Beware the false positive: freeing a multi-GB *written* fill file freezes the emulator's storage for minutes and any pending host start then "times out" — `fill-disk.sh` keeps a ballast for that reason. |

## Sentry conventions

* Resolve fixed issues **in the release** that ships the fix (`com.tortugapower.audiobookplayer@X.Y.Z+code`),
  never plain "resolved": builds stay in the field for months, and only "in release" ignores the
  stragglers while still reopening on a regression in the fixed build.
* The release labelled `1.0.0+14` is the public 1.1.0 build (its `versionName` was never bumped).
* `dev`-flavor builds do **not** report to Sentry unless `SENTRY_DEV_REPORTING=true` is in
  `local.properties`. Before that gate, emulator reproductions landed in the production issue list as
  fresh fingerprints (`environment:dev` on the 1.1.3+20 release) — the -1N / -1K / -1M / -1J issues
  were archived for that reason. When you do opt in, filter the issue list by `environment:prod`.
