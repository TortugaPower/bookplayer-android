package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

object SleepTimerManager {
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() +
            StorageMonitor.exceptionHandler { com.tortugapower.audiobookplayer.core.CoreContext.appContextOrNull }
    )
    private var timerJob: Job? = null
    /** Poll that watches for the armed chapter to end (end-of-chapter mode). */
    private var endOfChapterJob: Job? = null
    private var armed: EndOfChapterPolicy.Armed? = null

    /** How often the end-of-chapter poll checks the current chapter (matches iOS's ~1 Hz). */
    private const val END_OF_CHAPTER_POLL_MS = 1000L

    // StateFlow (not Compose mutableStateOf) so this can move into :core with PlaybackManager, which is
    // Compose-free; UI observes via collectAsState.
    private val _remainingMillis = MutableStateFlow(0L)
    val remainingMillis: StateFlow<Long> = _remainingMillis.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /** True while the timer is armed to stop at the end of the current chapter (no numeric countdown). */
    private val _isEndOfChapter = MutableStateFlow(false)
    val isEndOfChapter: StateFlow<Boolean> = _isEndOfChapter.asStateFlow()

    fun startTimer(minutes: Int) {
        startTimerMillis(minutes * 60 * 1000L)
    }

    fun startTimerMillis(millis: Long) {
        rememberLastEnabled((millis / 1000L).toInt())
        cancelInternal()
        _remainingMillis.value = millis
        _isActive.value = true

        timerJob = scope.launch {
            while (_remainingMillis.value > 0) {
                delay(1000)
                _remainingMillis.value -= 1000
            }
            _isActive.value = false
            PlaybackManager.pause()
        }
    }

    /**
     * Arm the timer to pause at the end of the currently-playing chapter. Mirrors iOS: pauses as soon
     * as playback leaves the armed chapter (natural end or manual skip), then auto-disarms. Works for
     * BOUND books (sub-book boundary, paused exactly via [PlaybackManager]'s media-item transition) and
     * single books with embedded chapters (position poll, ≤1s overshoot). The last chapter / book end
     * is handled by [PlaybackManager]'s end-of-stream path calling [onBookEnded].
     */
    fun startTimerUntilEndOfChapter() {
        rememberLastEnabled(PlaybackSettingsManager.SLEEP_TIMER_END_OF_CHAPTER)
        cancelInternal()
        val playable = PlaybackManager.currentPlayable.value ?: return
        val chapterIndex = playable.chapterIndexAt(PlaybackManager.currentWholeBookMs())
        if (chapterIndex < 0) return

        armed = EndOfChapterPolicy.Armed(playable.uuid, chapterIndex)
        _isActive.value = true
        _isEndOfChapter.value = true

        endOfChapterJob = scope.launch {
            while (_isEndOfChapter.value) {
                delay(END_OF_CHAPTER_POLL_MS)
                if (!PlaybackManager.isPlaying.value) continue
                val target = armed ?: break
                val current = PlaybackManager.currentPlayable.value
                val currentIndex = current?.chapterIndexAt(PlaybackManager.currentWholeBookMs()) ?: -1
                when (val decision = EndOfChapterPolicy.evaluate(target, current?.uuid, currentIndex)) {
                    EndOfChapterPolicy.Decision.Fire -> { fireEndOfChapter(); break }
                    EndOfChapterPolicy.Decision.Wait -> {}
                    is EndOfChapterPolicy.Decision.Rearm -> armed = decision.armed
                }
            }
        }
    }

    /**
     * Called by [PlaybackManager] on a real media-item (chapter) transition of a BOUND book — pauses
     * exactly at the boundary instead of waiting for the ≤1s poll.
     */
    fun onChapterBoundaryReached() {
        if (!_isEndOfChapter.value) return
        fireEndOfChapter()
    }

    /**
     * Called by [PlaybackManager] when the whole book ends (last chapter finished) while armed.
     * Playback has already stopped, so just disarm.
     */
    fun onBookEnded() {
        if (!_isEndOfChapter.value) return
        clearEndOfChapter()
    }

    private fun fireEndOfChapter() {
        PlaybackManager.pause()
        clearEndOfChapter()
    }

    private fun clearEndOfChapter() {
        endOfChapterJob?.cancel()
        endOfChapterJob = null
        armed = null
        _isEndOfChapter.value = false
        _isActive.value = false
    }

    /**
     * The user turned the timer off (player sheet, watch, deep link). Recorded as the last-enabled
     * value so the Auto Sleep Timer setting doesn't resurrect a timer the user explicitly cancelled —
     * iOS parity: `setTimer(.off)` updates `lastActiveState`, a natural expiry (`reset()`) doesn't.
     */
    fun stopTimer() {
        rememberLastEnabled(PlaybackSettingsManager.SLEEP_TIMER_OFF)
        cancelInternal()
    }

    /** Cancel whatever is running without touching the remembered last-enabled value. */
    private fun cancelInternal() {
        timerJob?.cancel()
        timerJob = null
        _remainingMillis.value = 0
        clearEndOfChapter()
    }

    fun configureTimerWithSeconds(seconds: Int) {
        when {
            seconds == PlaybackSettingsManager.SLEEP_TIMER_OFF -> stopTimer()
            seconds == PlaybackSettingsManager.SLEEP_TIMER_END_OF_CHAPTER -> startTimerUntilEndOfChapter()
            seconds > 0 -> startTimerMillis(seconds * 1000L)
        }
    }

    /**
     * Auto Sleep Timer: re-arm the timer the user last set (iOS `SleepTimer.restartTimer()` →
     * `setTimer(lastActiveState)`). A countdown restarts from its full duration, end-of-chapter re-arms
     * on the chapter now playing; "off" is a no-op. Called by [PlaybackManager] on user-initiated plays
     * only when the setting is on.
     */
    fun restartLastEnabledTimer() {
        val context = com.tortugapower.audiobookplayer.core.CoreContext.appContextOrNull ?: return
        scope.launch {
            val last = PlaybackSettingsManager.getLastEnabledSleepTimer(context).first()
            if (last == PlaybackSettingsManager.SLEEP_TIMER_OFF) return@launch
            configureTimerWithSeconds(last)
        }
    }

    private fun rememberLastEnabled(seconds: Int) {
        val context = com.tortugapower.audiobookplayer.core.CoreContext.appContextOrNull ?: return
        scope.launch(Dispatchers.IO) { PlaybackSettingsManager.setLastEnabledSleepTimer(context, seconds) }
    }
}
