package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager
import com.tortugapower.audiobookplayer.logic.ShortcutHelper
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent

class PlayerViewModel(
    application: Application,
    private val repository: LibraryRepository
) : AndroidViewModel(application) {
    private val appContext: Context get() = getApplication<Application>()

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

    private fun isItemLocal(item: com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity): Boolean = when {
        item.type == com.tortugapower.audiobookplayer.database.entities.ItemType.FOLDER -> true
        item.relativePath == null -> false
        else -> java.io.File(java.io.File(appContext.filesDir, "Processed"), item.relativePath!!).exists()
    }

    // Bookmark & Chapter States
    var showBookmarkConfirmation by mutableStateOf(false)
    var showAddNoteDialog by mutableStateOf(false)
    var showBookmarksList by mutableStateOf(false)
    var showChaptersList by mutableStateOf(false)
    var showCastSheet by mutableStateOf(false)
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

    // Whether the current item plays from a local file (vs. needs streaming). Computed off the main
    // thread (File.exists) so the player UI never touches the filesystem during composition.
    private val _isCurrentItemLocal = MutableStateFlow(false)
    val isCurrentItemLocal: StateFlow<Boolean> = _isCurrentItemLocal.asStateFlow()

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
    var listButtonOpens by mutableStateOf(PlaybackSettingsManager.LIST_OPENS_CHAPTERS)
    var useRemainingTime by mutableStateOf(true)
    var useChapterContext by mutableStateOf(false)

    init {
        // Load initial settings
        viewModelScope.launch {
            loadSettings(appContext)
        }

        // Observe settings changes continuously for real-time UI updates
        viewModelScope.launch {
            val context = appContext
            launch {
                PlaybackSettingsManager.getRewindInterval(context).collect { rewindInterval = it }
            }
            launch {
                PlaybackSettingsManager.getForwardInterval(context).collect { forwardInterval = it }
            }
            launch {
                PlaybackSettingsManager.getSmartRewindLimit(context).collect { smartRewindLimit = it }
            }
            launch {
                PlaybackSettingsManager.getListButtonOpens(context).collect { listButtonOpens = it }
            }
        }

        // Resolve local-vs-streamed off the main thread whenever the item changes (keeps File.exists
        // out of composition); collectLatest cancels a stale check if the item changes again.
        viewModelScope.launch {
            PlaybackManager.currentItem.collectLatest { item ->
                _isCurrentItemLocal.value = item != null && withContext(Dispatchers.IO) { isItemLocal(item) }
            }
        }

        // Observe Current Item and update lists (Using Stable collectLatest)
        viewModelScope.launch {
            PlaybackManager.currentItem.collectLatest { item ->
                if (item != null) {
                    // Update navigation states
                    hasNextItem = repository.getAdjacentItem(item.uuid, next = true) != null
                    hasPreviousItem = repository.getAdjacentItem(item.uuid, next = false) != null

                    // collectLatest automatically cancels this when the item changes.
                    launch {
                        repository.getBookmarksForBook(item.uuid).collect { _bookmarks.value = it }
                    }
                } else {
                    _bookmarks.value = emptyList()
                }
            }
        }

        // Chapters come from the single playback model (PlayableItem), which the player builds once on
        // load — for both BOUND books (sub-books) and single books (embedded markers / synthetic) — so
        // the chapter list always matches what's playing instead of being re-derived here.
        viewModelScope.launch {
            PlaybackManager.currentPlayable.collectLatest { playable ->
                _chapters.value = playable?.chapterEntities ?: emptyList()
            }
        }
    }
    fun jumpToStart() {
        PlaybackManager.seekTo(0)
        showMoreOptions = false
        notifySeek()
    }

    fun toggleFinished() {
        val item = PlaybackManager.currentItem.value ?: return
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
        // chapter.start is whole-book seconds (cumulative for BOUND, embedded offset for single books);
        // seekWholeBook maps it into whatever coordinate space the session currently exposes.
        PlaybackManager.seekWholeBook((chapter.start * 1000).toLong())
        showChaptersList = false
        notifySeek()
    }

    fun seekToBookmark(bookmark: BookmarkEntity) {
        PlaybackManager.seekTo((bookmark.time * 1000).toLong())
        showBookmarksList = false
        notifySeek()
    }

    fun addBookmark() {
        val item = PlaybackManager.currentItem.value ?: return
        if (PlaybackManager.player == null) return
        // Whole-book seconds, so a bookmark made in any context (and on a BOUND book) round-trips
        // through seekToBookmark -> seekWholeBook correctly.
        val currentTime = PlaybackManager.currentWholeBookMs() / 1000.0

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
    fun toggleCastSheet() { showCastSheet = !showCastSheet }

    fun startSleepTimer(minutes: Int) {
        com.tortugapower.audiobookplayer.logic.SleepTimerManager.startTimer(minutes)
        showSleepTimerMenu = false
    }

    fun startSleepTimerEndOfChapter() {
        com.tortugapower.audiobookplayer.logic.SleepTimerManager.startTimerUntilEndOfChapter()
        showSleepTimerMenu = false
    }

    fun stopSleepTimer() {
        com.tortugapower.audiobookplayer.logic.SleepTimerManager.stopTimer()
        showSleepTimerMenu = false
    }

    val sleepTimerActive: StateFlow<Boolean> = com.tortugapower.audiobookplayer.logic.SleepTimerManager.isActive
    val sleepTimerIsEndOfChapter: StateFlow<Boolean> =
        com.tortugapower.audiobookplayer.logic.SleepTimerManager.isEndOfChapter
    val sleepTimerRemaining: StateFlow<String> =
        com.tortugapower.audiobookplayer.logic.SleepTimerManager.remainingMillis
            .map { millis ->
                val totalSeconds = millis / 1000
                String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "0:00")

    fun loadSettings(context: Context) {
        viewModelScope.launch {
            var smartRewindVal = false
            var smartRewindLimitVal = 30
            var rewindIntervalVal = 30
            var forwardIntervalVal = 30
            var autoSleepVal = false
            var quickAction1Val = 1.3f
            var quickAction2Val = 2.0f
            var quickAction3Val = 3.0f
            var globalSpeedVal = false
            var progressBarSeekingVal = true
            var listButtonOpensVal = PlaybackSettingsManager.LIST_OPENS_CHAPTERS
            var useRemainingTimeVal = true
            var useChapterContextVal = false

            withContext(Dispatchers.IO) {
                smartRewindVal = PlaybackSettingsManager.getSmartRewind(context).first()
                smartRewindLimitVal = PlaybackSettingsManager.getSmartRewindLimit(context).first()
                rewindIntervalVal = PlaybackSettingsManager.getRewindInterval(context).first()
                forwardIntervalVal = PlaybackSettingsManager.getForwardInterval(context).first()
                autoSleepVal = PlaybackSettingsManager.getAutoSleepTimer(context).first()
                quickAction1Val = PlaybackSettingsManager.getQuickAction1(context).first()
                quickAction2Val = PlaybackSettingsManager.getQuickAction2(context).first()
                quickAction3Val = PlaybackSettingsManager.getQuickAction3(context).first()
                globalSpeedVal = PlaybackSettingsManager.getGlobalSpeedControl(context).first()
                progressBarSeekingVal = PlaybackSettingsManager.getProgressBarSeeking(context).first()
                listButtonOpensVal = PlaybackSettingsManager.getListButtonOpens(context).first()
                useRemainingTimeVal = PlaybackSettingsManager.getUseRemainingTime(context).first()
                useChapterContextVal = PlaybackSettingsManager.getUseChapterContext(context).first()
            }

            smartRewind = smartRewindVal
            smartRewindLimit = smartRewindLimitVal
            rewindInterval = rewindIntervalVal
            forwardInterval = forwardIntervalVal
            autoSleep = autoSleepVal
            quickAction1 = quickAction1Val
            quickAction2 = quickAction2Val
            quickAction3 = quickAction3Val
            globalSpeed = globalSpeedVal
            progressBarSeeking = progressBarSeekingVal
            listButtonOpens = listButtonOpensVal
            useRemainingTime = useRemainingTimeVal
            useChapterContext = useChapterContextVal
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

    fun createHomeScreenShortcut() {
        val item = currentItem.value ?: return
        ShortcutHelper.requestPinShortcut(appContext, item)
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
    val playbackSpeed: StateFlow<Float> get() = PlaybackManager.playbackSpeed
    val playbackVolume: StateFlow<Float> get() = PlaybackManager.playbackVolume
    val volumeBoost: StateFlow<Boolean> get() = PlaybackManager.volumeBoost
    val isPlaying: StateFlow<Boolean> get() = PlaybackManager.isPlaying
    val playbackState: StateFlow<Int> get() = PlaybackManager.playbackState
    val currentItem: StateFlow<com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity?> get() = PlaybackManager.currentItem
    val currentPlayable: StateFlow<com.tortugapower.audiobookplayer.logic.PlayableItem?> get() = PlaybackManager.currentPlayable
    val player get() = PlaybackManager.player
    val isTransitioning: StateFlow<Boolean> get() = PlaybackManager.isTransitioning

    fun setPlaybackSpeed(context: Context, speed: Float) { PlaybackManager.setPlaybackSpeed(context, speed) }
    fun setPlaybackVolume(context: Context, volume: Float) { PlaybackManager.setPlaybackVolume(context, volume) }
    fun toggleVolumeBoost(context: Context) { PlaybackManager.toggleVolumeBoost(context) }
    
    // The chapter the player is currently in — located from the whole-book position via the file-aware
    // timeline (handles multiple chapters per file). -1 if nothing is loaded.
    private fun currentChapterIndex(): Int =
        PlaybackManager.currentPlayable.value?.chapterIndexAt(PlaybackManager.currentWholeBookMs()) ?: -1

    // The <> chevrons always navigate chapters (iOS parity — not gated on chapter context), falling
    // through to the previous/next book only at the book's chapter boundaries.
    fun playNext(context: Context) {
        val currentChapters = chapters.value
        val currentIndex = currentChapterIndex()
        if (currentIndex != -1 && currentIndex < currentChapters.size - 1) {
            seekToChapter(currentChapters[currentIndex + 1])
            return
        }
        PlaybackManager.playNext(context)
    }

    fun playPrevious(context: Context) {
        val currentChapters = chapters.value
        val currentIndex = currentChapterIndex()
        if (currentIndex != -1 && currentChapters.isNotEmpty()) {
            // ms elapsed within the current chapter (mode-agnostic: whole-book position minus the
            // chapter's cumulative start). >3s in → restart the chapter; otherwise go to the previous.
            val msIntoChapter =
                PlaybackManager.currentWholeBookMs() - (currentChapters[currentIndex].start * 1000).toLong()
            if (msIntoChapter > com.tortugapower.audiobookplayer.logic.ChapterSkipPolicy.CHAPTER_START_THRESHOLD_MS) {
                seekToChapter(currentChapters[currentIndex])
                return
            } else if (currentIndex > 0) {
                seekToChapter(currentChapters[currentIndex - 1])
                return
            }
        }
        PlaybackManager.playPrevious(context) // near start & first chapter → previous book
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
        // Whole-book ms; seekWholeBook handles the BOUND book/chapter-context coordinate mapping.
        PlaybackManager.seekWholeBook(positionMs)
        notifySeek()
    }
}
