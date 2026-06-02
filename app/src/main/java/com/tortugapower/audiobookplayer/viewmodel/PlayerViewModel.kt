package com.tortugapower.audiobookplayer.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
    var showExtendedControls by mutableStateOf(false)
    var showMoreOptions by mutableStateOf(false)
    var showSleepTimerMenu by mutableStateOf(false)
    var showCustomSleepTimerPicker by mutableStateOf(false)

    // Trigger to notify UI of seek operations
    var seekTrigger by mutableIntStateOf(0)
        private set

    fun notifySeek() {
        seekTrigger++
    }

    // Bookmark & Chapter States
    var showBookmarkConfirmation by mutableStateOf(false)
    var showAddNoteDialog by mutableStateOf(false)
    var showBookmarksList by mutableStateOf(false)
    var showChaptersList by mutableStateOf(false)
    var currentBookmark: BookmarkEntity? by mutableStateOf(null)
    var isExistingBookmark by mutableStateOf(false)

    var hasNextItem by mutableStateOf(false)
        private set
    var hasPreviousItem by mutableStateOf(false)
        private set

    private val _bookmarks = MutableStateFlow<List<BookmarkEntity>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkEntity>> = _bookmarks.asStateFlow()

    private val _chapters = MutableStateFlow<List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>>(emptyList())
    val chapters: StateFlow<List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>> = _chapters.asStateFlow()

    var isRepeatEnabled by mutableStateOf(false)

    // Settings logic
    var smartRewind by mutableStateOf(true)
    var smartRewindLimit by mutableStateOf(30)
    var autoSleep by mutableStateOf(false)
    var quickAction1 by mutableStateOf(1.3f)
    var quickAction2 by mutableStateOf(2.0f)
    var quickAction3 by mutableStateOf(3.0f)
    var globalSpeed by mutableStateOf(false)
    var rewindInterval by mutableStateOf(30)
    var forwardInterval by mutableStateOf(30)
    var progressBarSeeking by mutableStateOf(true)
    var listButtonOpens by mutableStateOf("Chapters")
    var useRemainingTime by mutableStateOf(true)
    var useChapterContext by mutableStateOf(false)

    init {
        // Load initial settings
        viewModelScope.launch {
            val context = com.tortugapower.audiobookplayer.MainActivity.currentContext ?: return@launch
            loadSettings(context)
        }

        // Observe settings changes continuously for real-time UI updates
        viewModelScope.launch {
            val context = com.tortugapower.audiobookplayer.MainActivity.currentContext ?: return@launch
            launch {
                PlaybackSettingsManager.getRewindInterval(context).collect { rewindInterval = it }
            }
            launch {
                PlaybackSettingsManager.getForwardInterval(context).collect { forwardInterval = it }
            }
            launch {
                PlaybackSettingsManager.getSmartRewindLimit(context).collect { smartRewindLimit = it }
            }
        }

        // Observe Current Item and update lists (Using Stable collectLatest)
        viewModelScope.launch {
            snapshotFlow { PlaybackManager.currentItem }.collectLatest { item ->
                if (item != null) {
                    // Update navigation states
                    hasNextItem = repository.getAdjacentItem(item.uuid, next = true) != null
                    hasPreviousItem = repository.getAdjacentItem(item.uuid, next = false) != null

                    // Launch child coroutines to collect database flows
                    // collectLatest automatically cancels previous collections when item changes
                    launch {
                        repository.getBookmarksForBook(item.uuid).collect { _bookmarks.value = it }
                    }
                    launch {
                        if (item.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND) {
                            item.relativePath?.let { path ->
                                val subItems = repository.getItemsInPathSync(path)
                                var currentStart = 0.0
                                val volumeChapters = subItems.mapIndexed { index, subItem ->
                                    val chapter = com.tortugapower.audiobookplayer.database.entities.ChapterEntity(
                                        id = (index + 1).toLong(),
                                        bookUuid = item.uuid,
                                        title = subItem.title,
                                        start = currentStart,
                                        duration = subItem.duration,
                                        index = index
                                    )
                                    currentStart += subItem.duration
                                    chapter
                                }
                                _chapters.value = volumeChapters
                            }
                        } else {
                            repository.getChaptersForBook(item.uuid).collect { dbChapters ->
                                if (dbChapters.isEmpty()) {
                                    _chapters.value = listOf(
                                        com.tortugapower.audiobookplayer.database.entities.ChapterEntity(
                                            bookUuid = item.uuid,
                                            title = item.title,
                                            start = 0.0,
                                            duration = item.duration,
                                            index = 0
                                        )
                                    )
                                } else {
                                    _chapters.value = dbChapters
                                }
                            }
                        }
                    }
                } else {
                    _bookmarks.value = emptyList()
                    _chapters.value = emptyList()
                }
            }
        }
    }
    fun jumpToStart() {
        PlaybackManager.seekTo(0)
        showMoreOptions = false
        notifySeek()
    }

    fun toggleFinished() {
        val item = PlaybackManager.currentItem ?: return
        viewModelScope.launch {
            item.isFinished = !item.isFinished
            if (item.isFinished) {
                item.currentTime = item.duration
                PlaybackManager.seekTo((item.duration * 1000).toLong())
            } else {
                item.currentTime = 0.0
                PlaybackManager.seekTo(0)
            }
            repository.updateItem(item)
            showMoreOptions = false
            notifySeek()
        }
    }

    fun toggleRepeat() {
        isRepeatEnabled = !isRepeatEnabled
        PlaybackManager.player?.repeatMode = if (isRepeatEnabled) 2 else 0 // 2 = REPEAT_MODE_ONE, 0 = OFF
    }

    fun seekToChapter(chapter: com.tortugapower.audiobookplayer.database.entities.ChapterEntity) {
        val item = currentItem ?: return
        if (item.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND) {
            PlaybackManager.seekTo(chapter.index, 0L)
        } else {
            PlaybackManager.seekTo((chapter.start * 1000).toLong())
        }
        showChaptersList = false
        notifySeek()
    }

    fun seekToBookmark(bookmark: BookmarkEntity) {
        PlaybackManager.seekTo((bookmark.time * 1000).toLong())
        showBookmarksList = false
        notifySeek()
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

    fun toggleControlsSheet() { showControlsSheet = !showControlsSheet }
    fun toggleExtendedControls() { showExtendedControls = !showExtendedControls }
    fun toggleMoreOptions() { showMoreOptions = !showMoreOptions }
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
            rewindInterval = PlaybackSettingsManager.getRewindInterval(context).first()
            forwardInterval = PlaybackSettingsManager.getForwardInterval(context).first()
            autoSleep = PlaybackSettingsManager.getAutoSleepTimer(context).first()
            quickAction1 = PlaybackSettingsManager.getQuickAction1(context).first()
            quickAction2 = PlaybackSettingsManager.getQuickAction2(context).first()
            quickAction3 = PlaybackSettingsManager.getQuickAction3(context).first()
            globalSpeed = PlaybackSettingsManager.getGlobalSpeedControl(context).first()
            progressBarSeeking = PlaybackSettingsManager.getProgressBarSeeking(context).first()
            listButtonOpens = PlaybackSettingsManager.getListButtonOpens(context).first()
            useRemainingTime = PlaybackSettingsManager.getUseRemainingTime(context).first()
            useChapterContext = PlaybackSettingsManager.getUseChapterContext(context).first()
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

    fun updateGlobalSpeed(context: Context, enabled: Boolean) {
        globalSpeed = enabled
        viewModelScope.launch { PlaybackSettingsManager.setGlobalSpeedControl(context, enabled) }
    }

    fun updateProgressBarSeeking(context: Context, enabled: Boolean) {
        progressBarSeeking = enabled
        viewModelScope.launch { PlaybackSettingsManager.setProgressBarSeeking(context, enabled) }
    }

    fun updateListButtonOpens(context: Context, value: String) {
        listButtonOpens = value
        viewModelScope.launch { PlaybackSettingsManager.setListButtonOpens(context, value) }
    }

    fun updateUseRemainingTime(context: Context, enabled: Boolean) {
        useRemainingTime = enabled
        viewModelScope.launch { PlaybackSettingsManager.setUseRemainingTime(context, enabled) }
    }

    fun updateUseChapterContext(context: Context, enabled: Boolean) {
        useChapterContext = enabled
        viewModelScope.launch { PlaybackSettingsManager.setUseChapterContext(context, enabled) }
    }

    fun updateQuickAction1(context: Context, speed: Float) {
        quickAction1 = speed
        viewModelScope.launch { PlaybackSettingsManager.setQuickAction1(context, speed) }
    }

    fun updateQuickAction2(context: Context, speed: Float) {
        quickAction2 = speed
        viewModelScope.launch { PlaybackSettingsManager.setQuickAction2(context, speed) }
    }

    fun updateQuickAction3(context: Context, speed: Float) {
        quickAction3 = speed
        viewModelScope.launch { PlaybackSettingsManager.setQuickAction3(context, speed) }
    }

    fun updateRewindInterval(context: Context, seconds: Int) {
        rewindInterval = seconds
        viewModelScope.launch { PlaybackSettingsManager.setRewindInterval(context, seconds) }
    }

    fun updateForwardInterval(context: Context, seconds: Int) {
        forwardInterval = seconds
        viewModelScope.launch { PlaybackSettingsManager.setForwardInterval(context, seconds) }
    }

    fun updateSmartRewindLimit(context: Context, seconds: Int) {
        smartRewindLimit = seconds
        viewModelScope.launch { PlaybackSettingsManager.setSmartRewindLimit(context, seconds) }
    }

    // Proxy methods
    val playbackSpeed get() = PlaybackManager.playbackSpeed
    val playbackVolume get() = PlaybackManager.playbackVolume
    val volumeBoost get() = PlaybackManager.volumeBoost
    val isPlaying get() = PlaybackManager.isPlaying
    val playbackState get() = PlaybackManager.playbackState
    val currentItem get() = PlaybackManager.currentItem
    val player get() = PlaybackManager.player
    val isTransitioning get() = PlaybackManager.isTransitioning

    fun setPlaybackSpeed(context: Context, speed: Float) { PlaybackManager.setPlaybackSpeed(context, speed) }
    fun setPlaybackVolume(context: Context, volume: Float) { PlaybackManager.setPlaybackVolume(context, volume) }
    fun toggleVolumeBoost(context: Context) { PlaybackManager.toggleVolumeBoost(context) }
    
    fun playNext(context: Context) {
        if (useChapterContext) {
            val p = player
            val currentChapters = chapters.value
            if (p != null && currentChapters.isNotEmpty()) {
                val currentPos = p.currentPosition / 1000.0
                val currentIndex = currentChapters.indexOfFirst { currentPos >= it.start && currentPos < (it.start + it.duration) }
                if (currentIndex != -1 && currentIndex < currentChapters.size - 1) {
                    seekToChapter(currentChapters[currentIndex + 1])
                    return
                }
            }
        }
        PlaybackManager.playNext(context)
    }

    fun playPrevious(context: Context) {
        if (useChapterContext) {
            val p = player
            val currentChapters = chapters.value
            if (p != null && currentChapters.isNotEmpty()) {
                val currentPos = p.currentPosition / 1000.0
                val currentIndex = currentChapters.indexOfFirst { currentPos >= it.start && currentPos < (it.start + it.duration) }
                if (currentIndex != -1) {
                    // If more than 3 seconds into chapter, go to start of current chapter
                    if (currentPos - currentChapters[currentIndex].start > 3.0) {
                        seekToChapter(currentChapters[currentIndex])
                        return
                    } else if (currentIndex > 0) {
                        seekToChapter(currentChapters[currentIndex - 1])
                        return
                    }
                }
            }
        }
        PlaybackManager.playPrevious(context)
    }

    fun togglePlayPause() { PlaybackManager.togglePlayPause() }
    fun seekForward() { 
        PlaybackManager.seekForward() 
        notifySeek()
    }
    fun seekBackward() { 
        PlaybackManager.seekBackward() 
        notifySeek()
    }

    fun seekToAbsolute(positionMs: Long) {
        val item = currentItem ?: return
        if (item.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND) {
            val targetPosSecs = positionMs / 1000.0
            val currentChapters = chapters.value
            if (currentChapters.isNotEmpty()) {
                val targetIndex = currentChapters.indexOfFirst { targetPosSecs >= it.start && targetPosSecs < (it.start + it.duration) }
                if (targetIndex != -1) {
                    val relativeTimeMs = ((targetPosSecs - currentChapters[targetIndex].start) * 1000).toLong()
                    PlaybackManager.seekTo(targetIndex, relativeTimeMs)
                } else {
                    PlaybackManager.seekTo(positionMs)
                }
            } else {
                PlaybackManager.seekTo(positionMs)
            }
        } else {
            PlaybackManager.seekTo(positionMs)
        }
        notifySeek()
    }
}
