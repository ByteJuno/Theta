package io.github.bytejuno.thetacore.ui.theme

import android.content.Context

/** All production readers must use the same legacy palette until the first JSON commit. */
internal fun themePreferenceStore(context: Context): ThemePreferenceStore {
    val app = context.applicationContext
    val legacy = runCatching { app.getSharedPreferences(APPEARANCE_STORE, Context.MODE_PRIVATE)
        .getBoolean(DYNAMIC_KEY, true) }.getOrDefault(true)
    return ThemePreferenceStore(app.noBackupFilesDir, legacy)
}
