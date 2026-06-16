package com.tortugapower.audiobookplayer.logic

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.content.FileProvider
import com.tortugapower.audiobookplayer.BuildConfig
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.ui.components.SupportLinks
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Short build/diagnostic blob — used as the email **attachment** (`build-info.txt`) and the
 * copy-to-clipboard fallback. Mirrors iOS's compact `build-info.txt`: app build, OS, device, and —
 * when signed in — the account email + RevenueCat id. Synchronous (no DB/IO).
 */
fun buildSupportDebugInfo(account: AccountEntity?): String = buildString {
    appendLine("BookPlayer ${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}")
    appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
    account?.email?.let { appendLine("Account: $it") }
    (account?.revenuecatId ?: account?.id)?.let { appendLine("RevenueCat ID: $it") }
}.trimEnd()

/**
 * Full "Share debug information" dump (`bookplayer_debug_information.txt`), the Android equivalent
 * of iOS `DebugFileTransferable.generateDebugData()`: device/build header, library tree with
 * on-disk status, storage breakdown, and sync state + queued jobs. Pulls from Room + the filesystem,
 * so it is **suspending** and must run off the main thread.
 */
suspend fun buildDebugInformation(context: Context): String {
    val db = AppDatabase.getDatabase(context)
    val account = db.accountDao().getAccount()
    val items = db.libraryDao().getAllItemsSync()
    val tasks = db.syncTaskDao().getAllTasksSync()
    val processedDir = File(context.filesDir, "Processed")

    fun size(f: File): String =
        Formatter.formatFileSize(context, if (f.exists()) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L)

    return buildString {
        // --- Header ---
        appendLine("BookPlayer ${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE} (${BuildConfig.FLAVOR})")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Server: ${BuildConfig.BASE_URL}")

        // --- Library ---
        val books = items.filter { it.type == ItemType.BOOK }
        val folders = items.count { it.type == ItemType.FOLDER }
        val volumes = items.count { it.type == ItemType.BOUND }
        val downloaded = books.count { it.relativePath?.let { p -> File(processedDir, p).exists() } == true }
        appendLine()
        appendLine("--- Library ---")
        appendLine("Total items: ${items.size} (books: ${books.size}, folders: $folders, volumes: $volumes)")
        appendLine("Downloaded books: $downloaded/${books.size}")

        // Tree: one line per item, indented by path depth, with on-disk status for books.
        appendLine()
        appendLine("Library")
        appendLine(".")
        items.sortedBy { it.relativePath }.forEach { item ->
            val path = item.relativePath.orEmpty()
            val depth = path.count { it == '/' }
            val indent = "    ".repeat(depth)
            val name = path.substringAfterLast('/').ifEmpty { item.title }
            val flag = when (item.type) {
                ItemType.BOOK -> if (path.isNotEmpty() && File(processedDir, path).exists()) "[✓]" else "[✗]"
                else -> "[/]" // folder / volume
            }
            appendLine("$indent$flag $name")
        }

        // --- Storage Breakdown ---
        appendLine()
        appendLine("--- Storage Breakdown ---")
        appendLine("Audiobooks (Processed): ${size(processedDir)}")
        appendLine("Artwork cache:          ${size(File(context.filesDir, "Artworks"))}")
        appendLine("Import staging:         ${size(File(context.filesDir, "BPBackup"))}")
        appendLine("App cache:              ${size(context.cacheDir)}")
        appendLine("Free space:             ${Formatter.formatFileSize(context, context.filesDir.usableSpace)}")

        // --- Sync Debug Information ---
        appendLine()
        appendLine("--- Sync Debug Information ---")
        appendLine()
        appendLine("Profile: ${account?.email ?: "Not logged in"}")
        if (account != null) {
            appendLine("Account ID: ${account.id}")
            appendLine("RevenueCat ID: ${account.revenuecatId ?: account.id}")
            appendLine("Subscription tier: ${account.tier.name}")
        }

        appendLine()
        appendLine("-- Sync State --")
        val pending = tasks.count { it.status != SyncTaskStatus.COMPLETED }
        appendLine("Pending tasks: $pending")
        val lastSync = SyncStatusManager.lastSyncTimestamp.value
        val lastSyncText = if (lastSync == null || lastSync == 0L) "Never" else {
            // Fixed UTC format so timestamps are unambiguous across users/locales reading the report.
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(lastSync))
        }
        appendLine("Last sync: $lastSyncText")

        appendLine()
        appendLine("-- Queued Jobs (${tasks.size}) --")
        tasks.forEachIndexed { index, task ->
            appendLine()
            appendLine("[${index + 1}] ${task.jobType} (${task.status})")
            appendLine("  Task ID: ${task.taskID}")
            appendLine("  Queue: ${task.queueKey}")
            appendLine("  Attempts: ${task.attempts}")
            task.errorMessage?.let { appendLine("  Error: $it") }
            appendLine("  Payload: ${task.payload}")
        }
    }.trimEnd()
}

/**
 * Writes [content] to a cache file named [fileName] and returns a shareable `content://` URI via
 * [FileProvider] (authority `${applicationId}.fileprovider`). A raw `file://` URI can't be shared
 * on API 24+ (`FileUriExposedException`), hence the provider.
 */
fun writeSupportFile(context: Context, fileName: String, content: String): Uri {
    val file = File(context.cacheDir, fileName)
    file.writeText(content)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Builds the support-email intents — one `ACTION_SEND` per installed email app — carrying the
 * `build-info.txt` attachment. The URI is placed in `clipData` (plus `FLAG_GRANT_READ_URI_PERMISSION`)
 * so the system grants **transient** read access only to whichever target the user actually picks —
 * including through the multi-app chooser (`EXTRA_INITIAL_INTENTS`), where a bare `EXTRA_STREAM` grant
 * isn't reliably propagated on older APIs. Returns an empty list when no email app is installed.
 * Does disk I/O — call off the main thread.
 */
fun buildSupportEmailIntents(context: Context, account: AccountEntity?): List<Intent> {
    val attachment = writeSupportFile(context, "build-info.txt", buildSupportDebugInfo(account))
    val appVersion = "${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}"
    val pm = context.packageManager
    val emailPackages = pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)
        .map { it.activityInfo.packageName }
        .distinct()
    return emailPackages.mapNotNull { pkg ->
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(pkg)
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SupportLinks.SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_support_email_subject, appVersion))
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.settings_support_email_body))
            putExtra(Intent.EXTRA_STREAM, attachment)
            clipData = ClipData.newRawUri("build-info.txt", attachment)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.takeIf { it.resolveActivity(pm) != null }
    }
}
