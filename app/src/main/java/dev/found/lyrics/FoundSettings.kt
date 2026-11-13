package dev.found.lyrics

import android.content.Context
import android.content.SharedPreferences

/**
 * User preferences for the bubble and the notification.
 * Everything the customisation screen can change lives here.
 */
class FoundSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("found_settings", Context.MODE_PRIVATE)

    /** Master switch for the floating bubble. */
    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY, value).apply()

    /** Whether the lyrics also live in the notification shade. */
    var showNotification: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION, value).apply()

    /** Multiplier applied to the lyric text size (0.75 - 1.5). */
    var textScale: Float
        get() = prefs.getFloat(KEY_TEXT_SCALE, 1f)
        set(value) = prefs.edit().putFloat(KEY_TEXT_SCALE, value).apply()

    /** 0 = follow the cover art, otherwise a fixed ARGB colour. */
    var accentOverride: Int
        get() = prefs.getInt(KEY_ACCENT, 0)
        set(value) = prefs.edit().putInt(KEY_ACCENT, value).apply()

    /** 0 = top right, 1 = top left. */
    var corner: Int
        get() = prefs.getInt(KEY_CORNER, 0)
        set(value) = prefs.edit().putInt(KEY_CORNER, value).apply()

    /** Collapse the bubble into a pill when playback pauses. */
    var autoCollapse: Boolean
        get() = prefs.getBoolean(KEY_AUTO_COLLAPSE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_COLLAPSE, value).apply()

    companion object {
        const val KEY_OVERLAY = "overlay_enabled"
        const val KEY_NOTIFICATION = "show_notification"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_ACCENT = "accent_override"
        const val KEY_CORNER = "corner"
        const val KEY_AUTO_COLLAPSE = "auto_collapse"
    }
}
