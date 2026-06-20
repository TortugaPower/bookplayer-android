package com.tortugapower.audiobookplayer.logic

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.tortugapower.audiobookplayer.R

/**
 * Source of truth for the active launcher icon. Android has no `setAlternateIconName`; alternate
 * icons are modeled as `<activity-alias>` entries (one per [AppIcon]) and switched by enabling the
 * chosen alias and disabling the rest via [PackageManager.setComponentEnabledSetting]. The enabled
 * component is itself durable (persists across relaunch), so it — not a separate cache — is the
 * selection's source of truth.
 *
 * Icons other than Default / Retro are gated behind a paid tier on the picker ([AppIcon.free]),
 * mirroring the Themes screen.
 *
 * Asset convention (produced in Android Studio's Image Asset Studio): each alternate uses an
 * adaptive mipmap `ic_launcher_<snake(id)>` (+ `_round`) — isolated foreground + solid background
 * color.
 */
object AppIconManager {

    /**
     * The 7 launcher icons ported from iOS, in display order. `id` doubles as the
     * `<activity-alias>` suffix and the selection key — keep it in sync with `AndroidManifest.xml`.
     */
    val allIcons: List<AppIcon> = listOf(
        AppIcon("Default", R.string.app_icon_name_default, free = true, previewRes = R.mipmap.ic_launcher),
        AppIcon("Retro", R.string.app_icon_name_retro, free = true, previewRes = R.mipmap.ic_launcher_retro),
        AppIcon("FruitBased", R.string.app_icon_name_fruit_based, free = false, previewRes = R.mipmap.ic_launcher_fruit_based),
        AppIcon("RetroModern", R.string.app_icon_name_retro_modern, free = false, previewRes = R.mipmap.ic_launcher_retro_modern),
        AppIcon("Neon", R.string.app_icon_name_neon, free = false, previewRes = R.mipmap.ic_launcher_neon),
        AppIcon("AyuLight", R.string.app_icon_name_ayu_light, free = false, previewRes = R.mipmap.ic_launcher_ayu_light),
        AppIcon("Songs", R.string.app_icon_name_songs, free = false, previewRes = R.mipmap.ic_launcher_songs),
    )

    /** The factory icon (also the enabled alias on a pristine install). */
    val defaultIcon: AppIcon get() = allIcons.first()

    private fun componentName(context: Context, icon: AppIcon): ComponentName =
        ComponentName(context.packageName, "${context.packageName}.icon.${icon.id}")

    /**
     * The icon whose alias is the currently enabled launcher component. On a pristine install no
     * alias has been explicitly toggled (their state is `COMPONENT_ENABLED_STATE_DEFAULT`, which
     * defers to the manifest), so this falls back to [defaultIcon].
     */
    fun currentIcon(context: Context): AppIcon {
        val pm = context.packageManager
        return selectCurrent(allIcons, defaultIcon) { icon ->
            pm.getComponentEnabledSetting(componentName(context, icon)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
    }

    /**
     * Make [icon] the launcher icon: enable its alias, disable every other. `DONT_KILL_APP` keeps
     * the swap silent — though some OEM launchers only refresh the icon after the app is next closed.
     *
     * Returns true on success; false if the platform rejected the toggle (e.g. an OEM that blocks
     * alias changes, or an unknown component). The binder calls are synchronous — call off the main
     * thread — and the caller should surface an error and NOT advance its selection on false.
     */
    fun setIcon(context: Context, icon: AppIcon): Boolean {
        val pm = context.packageManager
        return try {
            applyIcon(allIcons, icon) { candidate, enabled ->
                pm.setComponentEnabledSetting(
                    componentName(context, candidate),
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            true
        } catch (t: Throwable) {
            android.util.Log.e("AppIconManager", "Failed to set app icon '${icon.id}'", t)
            false
        }
    }

    // region Pure core (no Android types) — kept internal so it can be unit-tested directly.

    /** First icon reported enabled by [isEnabled], else [default]. */
    internal fun selectCurrent(
        icons: List<AppIcon>,
        default: AppIcon,
        isEnabled: (AppIcon) -> Boolean,
    ): AppIcon = icons.firstOrNull(isEnabled) ?: default

    /** Invoke [setEnabled] for every icon, enabling only [target] (exactly-one-enabled). */
    internal fun applyIcon(
        icons: List<AppIcon>,
        target: AppIcon,
        setEnabled: (AppIcon, Boolean) -> Unit,
    ) = icons.forEach { setEnabled(it, it.id == target.id) }

    // endregion
}
