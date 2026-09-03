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

### Not yet scripted

| Issue | Planned recipe |
|---|---|
| -12 / -10 / -S / -V storage full | `fallocate` in `/data/local/tmp` until a few MB remain; run sync, import and a playback statistics tick. |
| -1H / -X sync-host promotion timeout | `bp-lowend-31`, 500-item library, cold start; or a debug flag blocking the main thread 12 s after launch. |

## Sentry conventions

* Resolve fixed issues **in the release** that ships the fix (`com.tortugapower.audiobookplayer@X.Y.Z+code`),
  never plain "resolved": builds stay in the field for months, and only "in release" ignores the
  stragglers while still reopening on a regression in the fixed build.
* The release labelled `1.0.0+14` is the public 1.1.0 build (its `versionName` was never bumped).
