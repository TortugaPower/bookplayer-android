package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus

/** One collapsible lane on the Queued Tasks screen */
data class QueuedTaskSection(
    val queueKey: String,
    /** In queue order, parked tasks included */
    val tasks: List<SyncTaskEntity>,
    /** Nothing in the lane can run until a parked task is resumed */
    val isBlocked: Boolean,
) {
    val pausedCount: Int get() = tasks.count { it.pause != null }
}

/**
 * Lanes in display order (iOS `groupedByLane`): the sync lane first, then alphabetically, each keeping
 * the queue's order for its rows. A drained lane has no section.
 */
fun List<SyncTaskEntity>.groupedByLane(): List<QueuedTaskSection> {
    val queued = filter { it.status != SyncTaskStatus.COMPLETED }
    val blocked = SyncTaskPicker.blockedLanes(queued)
    return queued.groupBy { it.queueKey }
        .toSortedMap(compareBy<String> { it != SyncTaskFactory.QUEUE_SYNC }.thenBy { it })
        .map { (queueKey, tasks) -> QueuedTaskSection(queueKey, tasks, queueKey in blocked) }
}
