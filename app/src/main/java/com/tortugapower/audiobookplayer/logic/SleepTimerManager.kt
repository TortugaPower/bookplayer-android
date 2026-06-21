package com.tortugapower.audiobookplayer.logic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

object SleepTimerManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null

    private val _remainingMillis = mutableLongStateOf(0L)
    var remainingMillis: Long
        get() = _remainingMillis.longValue
        set(value) { _remainingMillis.longValue = value }

    private val _isActive = mutableStateOf(false)
    var isActive: Boolean
        get() = _isActive.value
        set(value) { _isActive.value = value }

    fun startTimer(minutes: Int) {
        startTimerMillis(minutes * 60 * 1000L)
    }

    fun startTimerMillis(millis: Long) {
        stopTimer()
        _remainingMillis.longValue = millis
        _isActive.value = true
        
        timerJob = scope.launch {
            while (_remainingMillis.longValue > 0) {
                delay(1000)
                _remainingMillis.longValue -= 1000
            }
            _isActive.value = false
            PlaybackManager.togglePlayPause() // This will pause if playing
        }
    }

    fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _remainingMillis.longValue = 0
        _isActive.value = false
    }

    fun configureTimerWithSeconds(context: android.content.Context, seconds: Int) {
        when {
            seconds == -1 -> stopTimer()
            seconds == -2 -> startTimerUntilEndOfChapter(context)
            seconds > 0 -> startTimerMillis(seconds * 1000L)
        }
    }

    private fun startTimerUntilEndOfChapter(context: android.content.Context) {
        val currentItem = PlaybackManager.currentItem.value ?: return
        val currentPlayer = PlaybackManager.player ?: return
        val currentPos = currentPlayer.currentPosition / 1000.0

        scope.launch(Dispatchers.IO) {
            val db = com.tortugapower.audiobookplayer.database.AppDatabase.getDatabase(context)
            val chapters = db.libraryDao().getChaptersForBook(currentItem.uuid).first()
            val currentChapter = chapters.find { currentPos >= it.start && currentPos < (it.start + it.duration) }
            
            if (currentChapter != null) {
                val remainingInChapter = (currentChapter.start + currentChapter.duration) - currentPos
                launch(Dispatchers.Main) {
                    startTimerMillis((remainingInChapter * 1000).toLong())
                }
            } else {
                // Fallback to book end if no chapters found
                val remainingInBook = currentItem.duration - currentPos
                launch(Dispatchers.Main) {
                    startTimerMillis((remainingInBook * 1000).toLong())
                }
            }
        }
    }

    fun formatRemainingTime(): String {
        val totalSeconds = remainingMillis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
