package com.tortugapower.audiobookplayer.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val repository: LibraryRepository
) : ViewModel() {
    var showControlsSheet by mutableStateOf(false)
    var showMoreSettingsSheet by mutableStateOf(false)
    var showSleepTimerMenu by mutableStateOf(false)
    var showCustomSleepTimerPicker by mutableStateOf(false)

    // Bookmark & Chapter States
    var showBookmarkConfirmation by mutableStateOf(false)
    var showAddNoteDialog by mutableStateOf(false)
    var showBookmarksList by mutableStateOf(false)
    var showChaptersList by mutableStateOf(false)
    var currentBookmark: BookmarkEntity? by mutableStateOf(null)
    var isExistingBookmark by mutableStateOf(false)

    private val _bookmarks = MutableStateFlow<List<BookmarkEntity>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkEntity>> = _bookmarks.asStateFlow()

    private val _chapters = MutableStateFlow<List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>>(emptyList())
    val chapters: StateFlow<List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>> = _chapters.asStateFlow()

    var isRepeatEnabled by mutableStateOf(false)

    init {
        // Observe Current Item and update lists (Using Stable collectLatest)
        viewModelScope.launch {
            snapshotFlow { PlaybackManager.currentItem }.collectLatest { item ->
                if (item != null) {
                    // Launch child coroutines to collect database flows
                    // collectLatest automatically cancels previous collections when item changes
                    launch {
                        repository.getBookmarksForBook(item.uuid).collect { _bookmarks.value = it }
                    }
                    launch {
                        repository.getChaptersForBook(item.uuid).collect { _chapters.value = it }
                    }
                } else {
                    _bookmarks.value = emptyList()
                    _chapters.value = emptyList()
                }
            }
        }
    }

    fun jumpToStart() {
        PlaybackManager.player?.seekTo(0)
        showMoreSettingsSheet = false
    }

    fun toggleFinished() {
        val item = PlaybackManager.currentItem ?: return
        viewModelScope.launch {
            item.isFinished = !item.isFinished
            if (item.isFinished) {
                item.currentTime = item.duration
            } else {
                item.currentTime = 0.0
            }
            repository.updateItem(item)
            showMoreSettingsSheet = false
        }
    }

    fun toggleRepeat() {
        isRepeatEnabled = !isRepeatEnabled
        PlaybackManager.player?.repeatMode = if (isRepeatEnabled) 2 else 0 // 2 = REPEAT_MODE_ONE, 0 = OFF
    }

    fun seekToChapter(chapter: com.tortugapower.audiobookplayer.database.entities.ChapterEntity) {
        PlaybackManager.player?.seekTo((chapter.start * 1000).toLong())
        showChaptersList = false
    }

    fun addBookmark() {
        val item = PlaybackManager.currentItem ?: return
        val player = PlaybackManager.player ?: return
        val currentTime = (player.currentPosition / 1000).toDouble()

        viewModelScope.launch {
            val existing = repository.getBookmarkAtTime(item.uuid, currentTime)
            if (existing == null) {
                val newBookmark = BookmarkEntity(
                    bookUuid = item.uuid,
                    time = currentTime
                )
                val id = repository.addBookmark(newBookmark)
                currentBookmark = newBookmark.copy(id = id)
                isExistingBookmark = false
                showBookmarkConfirmation = true
            } else {
                currentBookmark = existing
                isExistingBookmark = true
                showBookmarkConfirmation = true
            }
        }
    }

    fun updateBookmarkNote(note: String) {
        val bookmark = currentBookmark ?: return
        viewModelScope.launch {
            bookmark.note = note
            repository.updateBookmark(bookmark)
            showAddNoteDialog = false
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmark)
        }
    }

    fun seekToBookmark(bookmark: BookmarkEntity) {
        PlaybackManager.player?.seekTo((bookmark.time * 1000).toLong())
        showBookmarksList = false
    }

    // Settings logic
    var smartRewind by mutableStateOf(true)
    var smartRewindLimit by mutableStateOf(30)
    var autoSleep by mutableStateOf(false)
    var quickAction1 by mutableStateOf(1.0f)
    var quickAction2 by mutableStateOf(2.0f)

    fun toggleControlsSheet() { showControlsSheet = !showControlsSheet }
    fun toggleMoreSettingsSheet() { showMoreSettingsSheet = !showMoreSettingsSheet }
    fun toggleSleepTimerMenu() { showSleepTimerMenu = !showSleepTimerMenu }
    fun toggleCustomSleepTimerPicker() { showCustomSleepTimerPicker = !showCustomSleepTimerPicker }

    fun startSleepTimer(minutes: Int) {
        com.tortugapower.audiobookplayer.logic.SleepTimerManager.startTimer(minutes)
        showSleepTimerMenu = false
    }

    fun stopSleepTimer() {
        com.tortugapower.audiobookplayer.logic.SleepTimerManager.stopTimer()
        showSleepTimerMenu = false
    }

    val sleepTimerActive get() = com.tortugapower.audiobookplayer.logic.SleepTimerManager.isActive
    val sleepTimerRemaining get() = com.tortugapower.audiobookplayer.logic.SleepTimerManager.formatRemainingTime()

    fun loadSettings(context: Context) {
        viewModelScope.launch {
            smartRewind = PlaybackSettingsManager.getSmartRewind(context).first()
            smartRewindLimit = PlaybackSettingsManager.getSmartRewindLimit(context).first()
            autoSleep = PlaybackSettingsManager.getAutoSleepTimer(context).first()
            quickAction1 = PlaybackSettingsManager.getQuickAction1(context).first()
            quickAction2 = PlaybackSettingsManager.getQuickAction2(context).first()
        }
    }

    fun updateSmartRewind(context: Context, enabled: Boolean) {
        smartRewind = enabled
        viewModelScope.launch { PlaybackSettingsManager.setSmartRewind(context, enabled) }
    }

    fun updateAutoSleep(context: Context, enabled: Boolean) {
        autoSleep = enabled
        viewModelScope.launch { PlaybackSettingsManager.setAutoSleepTimer(context, enabled) }
    }

    // Proxy methods
    val playbackSpeed get() = PlaybackManager.playbackSpeed
    val playbackVolume get() = PlaybackManager.playbackVolume
    val volumeBoost get() = PlaybackManager.volumeBoost
    val isPlaying get() = PlaybackManager.isPlaying
    val currentItem get() = PlaybackManager.currentItem
    val player get() = PlaybackManager.player

    fun setPlaybackSpeed(context: Context, speed: Float) { PlaybackManager.setPlaybackSpeed(context, speed) }
    fun setPlaybackVolume(context: Context, volume: Float) { PlaybackManager.setPlaybackVolume(context, volume) }
    fun toggleVolumeBoost(context: Context) { PlaybackManager.toggleVolumeBoost(context) }
    fun togglePlayPause() { PlaybackManager.togglePlayPause() }
    fun seekForward() { PlaybackManager.seekForward() }
    fun seekBackward() { PlaybackManager.seekBackward() }
}
