package com.tortugapower.audiobookplayer.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * The host [Activity] behind a (possibly wrapped) Compose [Context]. `LocalContext.current` inside
 * dialogs/sheets (and under theme wrappers) is a [ContextWrapper], NOT the Activity — a plain
 * `as? Activity` cast returns null there, which made billing flows silently no-op. Walk the
 * wrapper chain instead (same pattern as AuthViewModel.activityFrom).
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
