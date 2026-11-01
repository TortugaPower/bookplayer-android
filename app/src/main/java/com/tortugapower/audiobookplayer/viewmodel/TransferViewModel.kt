package com.tortugapower.audiobookplayer.viewmodel

import android.content.Context
import androidx.annotation.ColorInt
import androidx.lifecycle.ViewModel
import com.tortugapower.audiobookplayer.R
import com.tortugapower.browserdrop.BrowserDropServer

/**
 * Keeps the Transfer sheet's current [TransferSession] across configuration changes. One per activity, under a
 * fixed key: each opening of the sheet gets a new session ([session]) and the ended one is let go, so openings
 * don't pile up in the activity's `ViewModelStore`.
 */
class TransferViewModel(
    private val makeSession: (BrowserDropServer.PageSettings) -> TransferSession
) : ViewModel() {

    private var current: Pair<String, TransferSession>? = null

    /**
     * The session for the opening [key] names, made on its first call with [pageSettings]. A new key ends the
     * previous one.
     */
    fun session(key: String, pageSettings: BrowserDropServer.PageSettings): TransferSession {
        current?.let { (currentKey, session) ->
            if (currentKey == key) return session
            session.discard()
        }
        return makeSession(pageSettings).also { current = key to it }
    }

    override fun onCleared() {
        current?.second?.discard()
        current = null
    }

    companion object {
        /** iOS's `%d` / `%1$d` / `%2$d` become the upload page's `{count}` / `{done}` / `{total}`. */
        fun pageTemplate(text: String): String = text
            .replace("%1\$d", "{done}")
            .replace("%2\$d", "{total}")
            .replace("%d", "{count}")

        /** The upload page's title, color and text, in the app's language. [context] is the activity's. */
        fun pageSettings(context: Context, @ColorInt accentColor: Int): BrowserDropServer.PageSettings =
            pageSettings(context::getString, context.resources.configuration.locales[0].toLanguageTag(), accentColor)

        @androidx.annotation.VisibleForTesting
        internal fun pageSettings(
            getString: (Int) -> String,
            language: String,
            @ColorInt accentColor: Int
        ): BrowserDropServer.PageSettings {
            val strings = mapOf(
                "subtitle" to R.string.transfer_server_web_subtitle,
                "dropTitle" to R.string.transfer_server_web_drop_title,
                "dropHint" to R.string.transfer_server_web_folder_hint,
                "chooseFiles" to R.string.transfer_server_web_choose_files,
                "chooseFolder" to R.string.transfer_server_web_choose_folder,
                "retry" to R.string.transfer_server_web_retry,
                "uploading" to R.string.transfer_server_web_uploading,
                "uploaded" to R.string.transfer_server_web_uploaded,
                "failed" to R.string.transfer_server_web_failed,
                "summaryEmpty" to R.string.transfer_server_web_queue_empty,
                "skipped" to R.string.transfer_server_web_skipped,
                "queued" to R.string.transfer_web_queued,
                "summary" to R.string.transfer_web_summary,
                "waitingForDevice" to R.string.transfer_web_waiting,
                "deviceUnreachable" to R.string.transfer_web_unreachable,
                "notEnoughSpace" to R.string.transfer_web_not_enough_space,
                "tooLarge" to R.string.transfer_web_too_large,
                "unsupported" to R.string.transfer_web_unsupported,
                "pathConflict" to R.string.transfer_web_path_conflict,
                "doneHint" to R.string.transfer_web_done_hint,
                "loadError" to R.string.transfer_web_load_error
            ).mapValues { (_, id) -> pageTemplate(getString(id)) }
            return BrowserDropServer.PageSettings(
                title = getString(R.string.app_name),
                language = language,
                accentColor = accentColor,
                strings = strings
            )
        }
    }
}
