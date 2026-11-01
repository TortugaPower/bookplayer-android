package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity

/**
 * The fields an item update sends for each kind of change ([SyncTaskFactory.createUpdateTask]), as iOS's
 * `LibraryService` sends them: only what the change touched, in the server's units. Anything else would send this
 * device's copy of it, which can be older than the server's (a title renamed, a book reordered or finished on
 * another device) and would undo the newer change.
 */
object ItemUpdateFields {

    /** A progress save that sets the finished flag: the library's Jump to start, a restart from 0 (iOS `jumpToStart`). */
    fun progress(item: LibraryItemEntity): Map<String, Any?> = mapOf(
        "currentTime" to item.currentTime,
        "percentCompleted" to percentCompleted(item),
        "isFinished" to item.isFinished,
        "lastPlayDateTimestamp" to lastPlayDateSeconds(item),
    )

    /**
     * A playback save (iOS `updatePlaybackTime`): the position, its progress and the play date, without the finished
     * flag, which playback never clears; the save that reached the book's end sends it, finished (iOS
     * `markAsCompleted`). A flag sent with every save would un-finish a book finished on another device.
     */
    fun playback(item: LibraryItemEntity, reachedEnd: Boolean): Map<String, Any?> =
        progress(item) - "isFinished" + (if (reachedEnd) mapOf("isFinished" to true) else emptyMap())

    /**
     * A folder holding the book that played: its progress and play date, rolled up from the book (iOS
     * `recursiveFolderProgressUpdate`, `recursiveFolderLastPlayedDateUpdate`).
     */
    fun folderProgress(folder: LibraryItemEntity): Map<String, Any?> = mapOf(
        "percentCompleted" to percentCompleted(folder),
        "lastPlayDateTimestamp" to lastPlayDateSeconds(folder),
    )

    /** A folder holding a book just marked finished or unfinished: its rolled-up progress (iOS `markAsCompleted`). */
    fun folderPercent(folder: LibraryItemEntity): Map<String, Any?> = mapOf("percentCompleted" to percentCompleted(folder))

    /**
     * A folder whose contents changed (a move, a delete): its count, length and progress (iOS
     * `rebuildFolderDetails`). The server keeps a folder's details as the client wrote them.
     */
    fun folderDetails(folder: LibraryItemEntity): Map<String, Any?> = mapOf(
        "percentCompleted" to percentCompleted(folder),
        "duration" to folder.duration,
        "details" to LibraryContentsSync.serverFolderDetails(folder),
    )

    /** The item's place in its folder (iOS `freezeVisibleOrder`). */
    fun rank(item: LibraryItemEntity): Map<String, Any?> = mapOf("orderRank" to item.orderRank)

    /** The book's speed (iOS `updateBookSpeed`): left out while it was never set. */
    fun speed(item: LibraryItemEntity): Map<String, Any?> = mapOf("speed" to item.speed)

    /** The title and author the user edited (iOS `renameBook`, `updateDetails`). */
    fun details(item: LibraryItemEntity): Map<String, Any?> = mapOf(
        "title" to item.title,
        "details" to LibraryContentsSync.serverFolderDetails(item),
    )

    /**
     * Mark as finished or unfinished, from the player or the library (iOS `markAsFinished`): the flag, the position
     * (kept, or the start for a book unfinished at its end), and the progress.
     */
    fun finished(item: LibraryItemEntity): Map<String, Any?> = mapOf(
        "isFinished" to item.isFinished,
        "currentTime" to item.currentTime,
        "percentCompleted" to percentCompleted(item),
    )

    /** A folder made a volume, or a volume a folder (iOS `updateFolder`). */
    fun type(item: LibraryItemEntity): Map<String, Any?> = mapOf("type" to item.type.ordinal)

    /**
     * A play date cleared on purpose (a bound-volume conversion): an explicit 0, as iOS sends, so the server drops
     * its stored date. An absent one leaves it.
     */
    fun clearedLastPlayDate(): Map<String, Any?> = mapOf("lastPlayDateTimestamp" to 0L)

    // The API/iOS convention is 0..100; the local column is the 0..1 fraction.
    private fun percentCompleted(item: LibraryItemEntity): Double = item.percentCompleted * 100.0

    // Epoch SECONDS (the local column is ms), as iOS and the API use. Never played here: left out, so a date set
    // on another device stays.
    private fun lastPlayDateSeconds(item: LibraryItemEntity): Long? = item.lastPlayDate?.let { it / 1000 }
}
