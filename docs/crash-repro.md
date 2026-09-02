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

### Not yet scripted

| Issue | Planned recipe |
|---|---|
| -Q media3 `mergePlayerInfo` | Several controllers connected (UI, widget, Auto DHU, Wear); loop rapid switches between a 1-chapter and a 200-chapter book while toggling chapter context. Fix is media3 1.7.1 → 1.11.0 plus a `BookTimelinePlayer` invariant test. |
| -19 / -15 statistics FK | Play a book, delete it from the library while playing, pause/resume. |
| -1A duplicate media session id | Debug flag throwing after `MediaLibrarySession.Builder.build()`, then restart the service in the same process. |
| -12 / -10 / -S / -V storage full | `fallocate` in `/data/local/tmp` until a few MB remain; run sync, import and a playback statistics tick. |
| -1H / -X sync-host promotion timeout | `bp-lowend-31`, 500-item library, cold start; or a debug flag blocking the main thread 12 s after launch. |

## Sentry conventions

* Resolve fixed issues **in the release** that ships the fix (`com.tortugapower.audiobookplayer@X.Y.Z+code`),
  never plain "resolved": builds stay in the field for months, and only "in release" ignores the
  stragglers while still reopening on a regression in the fixed build.
* The release labelled `1.0.0+14` is the public 1.1.0 build (its `versionName` was never bumped).
