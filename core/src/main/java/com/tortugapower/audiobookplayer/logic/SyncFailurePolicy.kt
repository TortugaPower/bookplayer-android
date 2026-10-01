package com.tortugapower.audiobookplayer.logic

/** How much of the queue a parked task holds back */
enum class TaskPauseScope {
    /** Only this task waits; the rest of its lane keeps running (leaf tasks nothing later depends on) */
    TASK,

    /** The whole lane waits behind it: later tasks would build on a change the server never got */
    LANE,

    /** Every BookPlayer-server lane waits: the server rejected the account itself */
    ACCOUNT,
}

/**
 * A failure that says it can never succeed as it is: the API's coded 4xx, or a book the app itself
 * refuses to upload. [message] is the API's and is shown to the user; it embeds file names, so it is
 * never sent to Sentry.
 */
data class CodedFailure(val code: String, val message: String, val httpStatus: Int?)

/**
 * Thrown by a processor to hand a [CodedFailure] to the engine. The exception's own message carries
 * only the code and status, so a crash or error report never picks up the file names in the API's.
 */
class CodedFailureException(val failure: CodedFailure) :
    Exception("Coded failure ${failure.code} (HTTP ${failure.httpStatus ?: "none"})")

/** What the engine does with a failed task */
sealed interface SyncFailureAction {
    /** Uncoded or transient: retry after the usual delay, as always */
    data object Retry : SyncFailureAction

    /** Stop the task with this scope until the launch retry or the user's Retry */
    data class Park(val scope: TaskPauseScope) : SyncFailureAction

    /** A coded failure where nobody can see a parked task (the watch): drop it */
    data object Drop : SyncFailureAction

    /** The server rejected the account: confirm against RevenueCat, holding every server lane meanwhile */
    data object VerifyAccount : SyncFailureAction
}

/** The iOS app's parking rules (`SyncFailurePolicy` in TaskPause.swift), over Android's job types */
object SyncFailurePolicy {
    /** Codes about the account, not the task */
    val accountCodes = setOf("not_subscribed", "tier_required")

    /** A book over the upload limit: the app's own limit, not a failure, so its park isn't reported */
    const val FILE_TOO_LARGE = "file_too_large"

    /** Jobs that never call the BookPlayer API: their own failure handling stands */
    private val nonServerJobs = setOf(
        SyncTaskFactory.JOB_EXTERNAL_UPDATE,
        SyncTaskFactory.JOB_HARDCOVER_AUTO_MATCH,
        SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS,
    )

    /**
     * Jobs that change structure (what exists, where, under which uuid) that later tasks build on: the
     * lane stops behind them. Everything else, unknown job types included, parks alone.
     */
    private val structuralJobs = setOf(
        SyncTaskFactory.JOB_UPLOAD_METADATA,
        SyncTaskFactory.JOB_MOVE,
        SyncTaskFactory.JOB_RENAME_FOLDER,
        SyncTaskFactory.JOB_DELETE,
        SyncTaskFactory.JOB_DELETE_SHALLOW,
        SyncTaskFactory.JOB_SET_BOOKMARK,
        SyncTaskFactory.JOB_DELETE_BOOKMARK,
        SyncTaskFactory.JOB_MATCH_UUIDS,
        SyncTaskFactory.JOB_UPLOAD_EXTERNAL_RESOURCE,
        SyncTaskFactory.JOB_DELETE_EXTERNAL_RESOURCE,
        SyncTaskFactory.JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD,
    )

    /** null for anything uncoded (network, 5xx, cancellation): those keep retrying */
    fun codedFailure(error: Throwable?): CodedFailure? = (error as? CodedFailureException)?.failure

    /**
     * Only a coded failure parks: a code means the request can never succeed as sent. Anything uncoded
     * keeps retrying, so a new server failure mode never strands tasks.
     */
    fun action(error: Throwable?, jobType: String, parkingEnabled: Boolean): SyncFailureAction {
        val code = codedFailure(error)?.code ?: return SyncFailureAction.Retry
        // An account pause must never land on a media-server or Hardcover lane
        if (jobType in nonServerJobs) return SyncFailureAction.Retry
        if (code in accountCodes) return SyncFailureAction.VerifyAccount
        if (!parkingEnabled) return SyncFailureAction.Drop
        return SyncFailureAction.Park(scope(jobType))
    }

    fun scope(jobType: String): TaskPauseScope =
        if (jobType in structuralJobs) TaskPauseScope.LANE else TaskPauseScope.TASK
}
