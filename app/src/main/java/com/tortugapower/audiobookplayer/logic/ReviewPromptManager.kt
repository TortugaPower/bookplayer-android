package com.tortugapower.audiobookplayer.logic

import android.app.Activity
import android.util.Log
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.flow.first

/**
 * Requests a Play in-app review after a book was listened to the end, mirroring iOS's flow:
 * [PlaybackManager] arms the persisted `ask_review` flag when playback naturally reaches the end
 * of a book (iOS `PlayerManager` + UserDefaults), and [maybeAsk] consumes it when the player is in
 * the foreground (iOS `PlayerViewModel.requestReview`, fired on `.bookEnd` and on app-active with
 * the player shown).
 *
 * Play owns the display policy: `launchReviewFlow` is a REQUEST — Play enforces a per-user quota
 * and shows nothing when over it (or on non-Play installs, which also makes a RELEASE-only gate
 * unnecessary, and keeps Internal-App-Sharing testing possible). The API gives no signal about
 * whether the dialog appeared, by design — never sequence UI after this call.
 */
object ReviewPromptManager {

    /**
     * Consume the `ask_review` flag and hand the review request to Play. No-ops when the flag is
     * unset. The flag is cleared as soon as it's consumed — like iOS, one finished book yields at
     * most one request, even if Play chooses not to display it.
     *
     * @param launchReview test seam; production launches the Play review flow
     */
    suspend fun maybeAsk(
        activity: Activity,
        launchReview: suspend (Activity) -> Unit = ::launchPlayReviewFlow
    ) {
        val context = activity.applicationContext
        if (!PlaybackSettingsManager.getAskReview(context).first()) return
        PlaybackSettingsManager.setAskReview(context, false)
        try {
            launchReview(activity)
            Log.d("ReviewPromptManager", "⭐ In-app review requested (Play decides visibility)")
        } catch (e: Exception) {
            // Best-effort: a failed request (no Play services, etc.) is not worth surfacing.
            Log.w("ReviewPromptManager", "In-app review request failed", e)
        }
    }

    private suspend fun launchPlayReviewFlow(activity: Activity) {
        val manager = ReviewManagerFactory.create(activity)
        manager.launchReview(activity, manager.requestReview())
    }
}
