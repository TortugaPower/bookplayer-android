package com.tortugapower.audiobookplayer.service

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.C
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Owns the [LoudnessEnhancer] volume-boost effect and runs every call on it off the caller's thread.
 *
 * Creating or releasing an audio effect is a synchronous binder transaction into audioserver
 * (`AudioFlinger::createEffect`). On some devices that call stalls for seconds. The 1.1.2 build
 * constructed the effect inside ExoPlayer's `onAudioSessionIdChanged`, which runs on the main
 * thread, and the result was a Background ANR with the main thread parked in
 * `LoudnessEnhancer.<init>` (Sentry ANDROID-BOOKPLAYER-11). Here callers only post commands; the
 * effect lives on a dedicated single-thread executor, so a stalled audioserver stalls that thread
 * and nothing else.
 *
 * Commands apply in order: "attach to B" after "attach to A" always ends with B live and A
 * released, and an [setEnabled] posted before the effect exists is applied when it is created.
 */
class LoudnessBooster(
    private val factory: (audioSessionId: Int) -> Effect = { RealEffect(LoudnessEnhancer(it)) },
    executor: Executor? = null,
) {
    /** The subset of [LoudnessEnhancer] this class uses; injectable for tests. */
    interface Effect {
        fun setTargetGain(millibels: Int)
        fun setEnabled(enabled: Boolean)
        fun release()
    }

    private class RealEffect(private val enhancer: LoudnessEnhancer) : Effect {
        override fun setTargetGain(millibels: Int) = enhancer.setTargetGain(millibels)
        override fun setEnabled(enabled: Boolean) { enhancer.enabled = enabled }
        override fun release() = enhancer.release()
    }

    private val ownsExecutor = executor == null
    private val executor: Executor =
        executor ?: Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME) }

    // The three fields below are touched only on the executor thread.
    private var effect: Effect? = null
    private var enabled = false
    private var released = false

    /** Bind the effect to [audioSessionId], releasing any previous instance. Returns at once. */
    fun attach(audioSessionId: Int) = post {
        dropEffect()
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return@post
        effect = try {
            factory(audioSessionId).also {
                it.setTargetGain(TARGET_GAIN_MB)
                it.setEnabled(enabled)
            }
        } catch (e: Exception) {
            // Devices without the effect, or with a broken effect HAL, throw here: boost is a no-op.
            Log.w(TAG, "LoudnessEnhancer unavailable for session $audioSessionId: $e")
            null
        }
    }

    /** Turn the boost on or off; remembered for effects created later. Returns at once. */
    fun setEnabled(value: Boolean) = post {
        enabled = value
        try {
            effect?.setEnabled(value)
        } catch (e: Exception) {
            Log.w(TAG, "LoudnessEnhancer.setEnabled($value) failed: $e")
        }
    }

    /** Release the effect and stop accepting commands. Returns at once. */
    fun release() = post {
        dropEffect()
        released = true
        if (ownsExecutor) (executor as ExecutorService).shutdown()
    }

    private fun dropEffect() {
        try {
            effect?.release()
        } catch (e: Exception) {
            Log.w(TAG, "LoudnessEnhancer.release failed: $e")
        }
        effect = null
    }

    private fun post(command: () -> Unit) {
        try {
            executor.execute { if (!released) command() }
        } catch (_: RejectedExecutionException) {
            // released and the executor is shut down
        }
    }

    companion object {
        private const val TAG = "LoudnessBooster"
        const val THREAD_NAME = "LoudnessBooster"
        /** 10 dB, roughly double the perceived loudness. */
        const val TARGET_GAIN_MB = 1000
    }
}
