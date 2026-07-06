package com.tortugapower.audiobookplayer.database.entities

/**
 * Common data for tasks that work on a specific relative path.
 */
interface PathTask {
    val relativePath: String
}

data class UploadTask(
    override val relativePath: String,
    val originalFileName: String,
    val title: String,
    val details: String,
    val speed: Double?,
    val currentTime: Double,
    val duration: Double,
    val percentCompleted: Double,
    val isFinished: Boolean,
    val orderRank: Int,
    val lastPlayDateTimestamp: Double?,
    val type: Int,
    val uuid: String,
    val provider: String? = null
) : PathTask

data class UpdateTask(
    override val relativePath: String,
    val title: String? = null,
    val details: String? = null,
    val speed: Double? = null,
    val currentTime: Double? = null,
    val duration: Double? = null,
    val percentCompleted: Double? = null,
    val isFinished: Boolean? = null,
    val orderRank: Int? = null,
    val lastPlayDateTimestamp: Double? = null,
    val type: Int? = null,
    val uuid: String
) : PathTask

data class MoveTask(
    override val relativePath: String,
    val origin: String,
    val destination: String,
    val uuid: String
) : PathTask

data class DeleteTask(
    override val relativePath: String,
    val jobType: String, // delete or shallowDelete
    val uuid: String
) : PathTask

data class DeleteBookmarkTask(
    override val relativePath: String,
    val time: Double,
    val uuid: String
) : PathTask

data class SetBookmarkTask(
    override val relativePath: String,
    val time: Double,
    val note: String? = null,
    val uuid: String
) : PathTask

data class RenameFolderTask(
    override val relativePath: String,
    val name: String,
    val uuid: String
) : PathTask

data class ArtworkUploadTask(
    override val relativePath: String,
    val uuid: String
) : PathTask

data class MatchUuidsTask(
    val uuids: Map<String, String>
)

data class ExternalUpdateTask(
    val providerName: String,
    val providerId: String,
    val title: String? = null,
    val details: String? = null,
    val currentTime: Double? = null,
    val percentCompleted: Double? = null,
    val isFinished: Boolean? = null,
    val lastPlayDateTimestamp: Double? = null
)

data class ConcurrentUploadTask(
    val filePath: String,
    val remotePath: String? = null,
    val uuid: String
)

data class ExternalResourceToDownloadTask(
    val uuid: String,
    val uploaded: Boolean
)
