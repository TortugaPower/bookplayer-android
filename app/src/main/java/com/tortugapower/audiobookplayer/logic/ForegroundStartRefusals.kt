package com.tortugapower.audiobookplayer.logic

import android.app.ActivityManager
import android.content.ComponentName
import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel

/**
 * Records the foreground starts Android refuses to the playback service — ANDROID-BOOKPLAYER-21's
 * condition: a resume with no user gesture behind it (the watch's remote play, a controller command)
 * after media3 dropped the foreground ten minutes into a pause, while nothing else of ours is a
 * foreground service. Android 12+ grants a package no way to promote itself from there — the rig in
 * `scripts/chaos/remote-resume-after-demotion.sh` measures every path, including the app dispatching a
 * media key to itself — so, as Pocket Casts does, this counts and reports instead of working around
 * the OS: media3 already catches the exception, the audio the user asked for keeps playing in a plain
 * background service, and the next gesture (a headset press, opening the app) brings the notification
 * back. The report is a handled Sentry event, never a crash: one per streak — media3 retries the
 * promotion on every notification update while playing (chapter, metadata, artwork), so the later
 * refusals of a streak become `fgs` breadcrumbs, which the next event carries with the streak length.
 */
class ForegroundStartRefusals(
    private val report: (occurrence: Int, playbackContinued: Boolean) -> Unit = { o, p -> reportToSentry(o, p) },
) {
    private var occurrences = 0

    /** A refusal has happened since the last promotion — the only time a promotion check is worth its cost. */
    val inStreak: Boolean get() = occurrences > 0

    /** media3's `Listener.onForegroundServiceStartNotAllowedException`. */
    fun onRefused(playbackContinued: Boolean) {
        occurrences += 1
        report(occurrences, playbackContinued)
    }

    /** A promotion went through: the next refusal starts a new streak. */
    fun onPromoted() {
        occurrences = 0
    }

    companion object {
        private const val TAG = "ForegroundStartRefusals"

        /**
         * Whether [self] is a foreground service right now, read from ActivityManager's own service record
         * (`isForeground`). This is the signal that ends a streak — NOT `Service.getForegroundServiceType()`:
         * that keeps returning the last type after media3 has demoted the service (measured on API 31 with
         * `scripts/chaos/remote-resume-after-demotion.sh`), so a check on it "promoted" after every refusal
         * and each refused attempt became its own Sentry event. `getRunningServices` is deprecated for
         * listing other apps' services; for the caller's own services it is documented to keep working.
         */
        fun isForeground(services: List<ActivityManager.RunningServiceInfo>, self: ComponentName): Boolean =
            services.any { it.service == self && it.foreground }

        fun reportToSentry(occurrence: Int, playbackContinued: Boolean) {
            Log.w(TAG, "foreground start refused (occurrence $occurrence, playback continued: $playbackContinued)")
            if (occurrence == 1) {
                Sentry.captureMessage("Playback foreground start refused", SentryLevel.WARNING) { scope ->
                    scope.setTag("fgs.playback_continued", playbackContinued.toString())
                }
            } else {
                Sentry.addBreadcrumb(
                    Breadcrumb.info("foreground start refused again (#$occurrence, playing=$playbackContinued)")
                        .apply { category = "fgs" }
                )
            }
        }
    }
}
