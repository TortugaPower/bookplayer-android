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
            buildBound(item, repository.getItemsInPathSync(item.relativePath ?: ""))
        } else {
            buildSingle(item, repository.getChaptersForBook(item.uuid).first())
        }

    /** BOUND book: each BOOK sub-item becomes one chapter with a cumulative whole-book [start]. */
    fun buildBound(item: LibraryItemEntity, subItems: List<LibraryItemEntity>): PlayableItem {
        var cumulative = 0.0
        val chapters = subItems
            .filter { it.type == ItemType.BOOK }
            .mapIndexed { index, sub ->
                PlayableChapter(
                    title = sub.title,
                    author = sub.author ?: item.author,
                    start = cumulative,
                    duration = sub.duration,
                    index = index,
                    relativePath = sub.relativePath,
                    remoteURL = sub.remoteURL,
                    artworkURL = sub.artworkURL ?: item.artworkURL,
                    uuid = sub.uuid
                ).also { cumulative += sub.duration }
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
