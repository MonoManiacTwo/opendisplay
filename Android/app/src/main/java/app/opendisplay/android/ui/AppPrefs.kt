package app.opendisplay.android.ui

import android.content.Context

/** The receiver's own settings; same keys and meaning as the iOS app's @AppStorage. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var showAnalytics: Boolean
        get() = prefs.getBoolean("showAnalytics", false)
        set(v) = prefs.edit().putBoolean("showAnalytics", v).apply()

    var hasConnectedBefore: Boolean
        get() = prefs.getBoolean("hasConnectedBefore", false)
        set(v) = prefs.edit().putBoolean("hasConnectedBefore", v).apply()

    var onboardingDismissed: Boolean
        get() = prefs.getBoolean("onboardingDismissed", false)
        set(v) = prefs.edit().putBoolean("onboardingDismissed", v).apply()
}
