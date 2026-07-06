package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import kotlinx.coroutines.flow.first

/**
 * Builds a [PlayableItem] from a library entity — the Android analogue of iOS
 * `PlaybackService.getPlayableItem(from:)`. Centralizes the chapter construction that used to be
 * duplicated across `PlaybackManager` (load + restore) and `PlayerViewModel`.
 *
 * The [buildBound]/[buildSingle] overloads take already-fetched data (so a caller that has the
 * sub-books / DB chapters on hand doesn't re-query); [build] is the convenience that fetches.
 */
object PlayableItemBuilder {

    suspend fun build(item: LibraryItemEntity, repository: LibraryRepository): PlayableItem =
        if (item.type == ItemType.BOUND) {
            val subItems = repository.getItemsInPathSync(item.relativePath ?: "")
            val chaptersBySubBook = subItems
                .filter { it.type == ItemType.BOOK }
                .associate { it.uuid to repository.getChaptersForBook(it.uuid).first() }
            buildBound(item, subItems, chaptersBySubBook)
        } else {
            buildSingle(item, repository.getChaptersForBook(item.uuid).first())
        }

    /**
     * BOUND book: flatten every sub-book's embedded chapters into one continuous, globally-indexed
     * chapter list (iOS `getPlayableChapters(folder:)` parity). Each chapter carries its whole-book
     * `start` (cumulative) plus a `chapterOffset` — its start WITHIN its file — which is 0 when the file
     * contributes a single chapter. A sub-book with no stored chapters becomes one chapter spanning it.
     * [chaptersBySubBook] maps a sub-book uuid to its FILE-LOCAL stored chapters.
     */
    fun buildBound(
        item: LibraryItemEntity,
        subItems: List<LibraryItemEntity>,
        chaptersBySubBook: Map<String, List<ChapterEntity>>
    ): PlayableItem {
        var currentDuration = 0.0
        var globalIndex = 0
        val chapters = ArrayList<PlayableChapter>()
        for (sub in subItems.filter { it.type == ItemType.BOOK }) {
            val stored = chaptersBySubBook[sub.uuid]?.sortedBy { it.index }.orEmpty()
            val nested: List<Pair<String, Double>> =
                if (stored.isNotEmpty()) stored.map { it.title to it.duration }
                else listOf(sub.title to sub.duration)
            val multiChapterFile = nested.size > 1
            var localCurrent = 0.0
            for ((title, dur) in nested) {
                chapters.add(
                    PlayableChapter(
                        title = title,
                        author = sub.author ?: item.author,
                        start = currentDuration,
                        duration = dur,
                        index = globalIndex,
                        relativePath = sub.relativePath,
                        remoteURL = sub.remoteURL,
                        artworkURL = sub.artworkURL ?: item.artworkURL,
                        chapterOffset = if (multiChapterFile) localCurrent else 0.0,
                        uuid = sub.uuid
                    )
                )
                currentDuration += dur
                localCurrent += dur
                globalIndex++
            }
        }
        // Derive whole-book duration from the chapters (matches iOS); fall back to the stored aggregate.
        val duration = chapters.lastOrNull()?.end ?: item.duration
        return PlayableItem(
            uuid = item.uuid,
            title = item.title,
            author = item.author,
            artworkURL = item.artworkURL,
            relativePath = item.relativePath,
            parentFolder = item.parentFolderUuid,
            isBoundBook = true,
            chapters = chapters,
            currentTime = item.currentTime,
            duration = duration,
            percentCompleted = item.percentCompleted,
            isFinished = item.isFinished
        )
    }

    /** Single book: chapters from its embedded markers, or one synthetic chapter spanning the file. */
    fun buildSingle(item: LibraryItemEntity, dbChapters: List<ChapterEntity>): PlayableItem {
        val chapters = if (dbChapters.isEmpty()) {
            listOf(
                PlayableChapter(
                    title = item.title,
                    author = item.author,
                    start = 0.0,
                    duration = item.duration,
                    index = 0,
                    relativePath = item.relativePath,
                    remoteURL = item.remoteURL,
                    artworkURL = item.artworkURL,
                    uuid = item.uuid
                )
            )
        } else {
            dbChapters.map {
                PlayableChapter(
                    title = it.title,
                    author = item.author,
                    start = it.start,
                    duration = it.duration,
                    index = it.index,
                    relativePath = item.relativePath,
                    remoteURL = item.remoteURL,
                    artworkURL = item.artworkURL,
                    uuid = item.uuid
                )
            }
        }
        return PlayableItem(
            uuid = item.uuid,
            title = item.title,
            author = item.author,
            artworkURL = item.artworkURL,
            relativePath = item.relativePath,
            parentFolder = item.parentFolderUuid,
            isBoundBook = false,
            chapters = chapters,
            currentTime = item.currentTime,
            duration = item.duration,
            percentCompleted = item.percentCompleted,
            isFinished = item.isFinished
        )
    }
}
