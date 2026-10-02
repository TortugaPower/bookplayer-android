package com.tortugapower.audiobookplayer.logic

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.gson.Gson
import com.tortugapower.audiobookplayer.BuildConfig
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What Report sends for a parked task (iOS `SyncPauseReport`): the task and why it stopped, every
 * queued task with its state, and the local library (paths with uuids), so support can see what the
 * device has and what it's still trying to tell the server. The server side is looked up from its own
 * records, so nothing is fetched here. It goes out through the user's own email, so paths and the
 * API's message are included, unlike the Sentry report.
 */
data class SyncPauseReport(
    val pausedTask: SyncTaskEntity,
    /** In queue order */
    val queuedTasks: List<SyncTaskEntity>,
    /** relativePath to uuid */
    val library: List<Pair<String, String>>,
    val appVersion: String,
    val device: String,
) {
    val subject: String
        get() = "Sync paused (${pausedTask.pause?.errorCode ?: "unknown"}) - BookPlayer $appVersion"

    val text: String
        get() = buildString {
            appendLine("BookPlayer sync report")
            appendLine("App: $appVersion")
            appendLine("Device: $device")

            appendLine()
            appendLine("-- Paused task --")
            append(describe(pausedTask))
            pausedTask.pause?.let { pause ->
                appendLine("  Message: ${pause.message}")
                appendLine("  Paused at: ${utc(pause.pausedAt)}")
                appendLine("  Sentry event: ${pause.sentryEventId ?: "none"}")
            }

            appendLine()
            appendLine("-- Queued tasks (${queuedTasks.size}) --")
            queuedTasks.forEachIndexed { index, task ->
                appendLine()
                append("[${index + 1}] ").append(describe(task))
            }

            appendLine()
            appendLine("-- Local library (${library.size}) --")
            library.sortedBy { it.first }.forEach { (path, uuid) ->
                val indent = "    ".repeat(path.count { it == '/' })
                appendLine("$indent${path.substringAfterLast('/')} [$uuid]")
            }
        }

    private fun describe(task: SyncTaskEntity): String = buildString {
        appendLine("${task.jobType} · lane ${task.queueKey}")
        val pause = task.pause
        if (pause != null) {
            val status = pause.httpStatus?.let { " $it" }.orEmpty()
            appendLine("  Status: paused (${pause.scope.name.lowercase()}) · ${pause.errorCode}$status")
        } else {
            appendLine("  Status: ${task.status.name.lowercase()}")
        }
        appendLine("  Task ID: ${task.id}")
        appendLine("  Item: ${task.taskID}")
        relativePath(task)?.let { appendLine("  Path: $it") }
    }

    companion object {
        const val FILE_NAME = "bookplayer_sync_report.txt"
        private val gson = Gson()

        /** The support email's version string: version-code plus the tier suffix (iOS's suffixes) */
        fun appVersion(tier: AccountTier?): String {
            val suffix = when (tier) {
                AccountTier.PRO -> "c"
                AccountTier.LITE -> "l"
                AccountTier.PLUS -> "p"
                AccountTier.FREE, null -> ""
            }
            return "${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}$suffix"
        }

        fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

        private fun relativePath(task: SyncTaskEntity): String? =
            runCatching { gson.fromJson(task.payload, Map::class.java)?.get("relativePath") as? String }.getOrNull()

        // Fixed UTC, so support reads the same time whatever the user's locale
        private fun utc(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(millis))
    }
}

/**
 * Writes the report as a file and returns the intents that send it: one per email app, addressed to
 * support with the file attached (as [buildSupportEmailIntents] does), or, with no email app, the share
 * sheet for the same file (iOS falls back the same way when Mail isn't set up). Does disk I/O; call off
 * the main thread, then hand the result to [launchSyncPauseReport].
 */
fun buildSyncPauseReportIntents(context: Context, report: SyncPauseReport): SyncPauseReportIntents {
    val attachment = writeSupportFile(context, SyncPauseReport.FILE_NAME, report.text)
    val pm = context.packageManager
    val emailPackages = pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)
        .map { it.activityInfo.packageName }
        .distinct()
    val email = emailPackages.mapNotNull { pkg ->
        reportIntent(context, report, attachment).apply {
            setPackage(pkg)
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SupportLinks.SUPPORT_EMAIL))
        }.takeIf { it.resolveActivity(pm) != null }
    }
    return SyncPauseReportIntents(email = email, share = reportIntent(context, report, attachment))
}

class SyncPauseReportIntents(val email: List<Intent>, val share: Intent)

/** Opens the email composer, or the share sheet when there's no email app to open */
fun launchSyncPauseReport(context: Context, intents: SyncPauseReportIntents) {
    if (launchSupportEmail(context, intents.email)) return
    runCatching { context.startActivity(Intent.createChooser(intents.share, null)) }
}

private fun reportIntent(context: Context, report: SyncPauseReport, attachment: Uri) =
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, report.subject)
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.sync_report_email_body))
        putExtra(Intent.EXTRA_STREAM, attachment)
        clipData = ClipData.newRawUri(SyncPauseReport.FILE_NAME, attachment)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
