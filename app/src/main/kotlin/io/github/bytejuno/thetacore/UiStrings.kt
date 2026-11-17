package io.github.bytejuno.thetacore

import android.app.LocaleManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Resource IDs are stable semantic keys; a future language overlay can resolve them here.
 * Never store localized strings in process-owned managers: store UiText and resolve at render time.
 */
internal data class UiText(val key: Int, val arguments: List<Any> = emptyList())
internal fun uiText(key: Int, vararg arguments: Any) = UiText(key, arguments.toList())

internal class UiStrings(private val resources: Resources) {
    fun get(key: Int, vararg arguments: Any): String = resources.getString(key, *arguments)
    fun dateTime(timestamp: Long): String = java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.DEFAULT, java.text.DateFormat.DEFAULT, resources.configuration.locales[0],
    ).format(java.util.Date(timestamp))
    fun get(text: UiText): String = get(text.key, *text.arguments.map {
        if (it is UiText) get(it) else it
    }.toTypedArray())
}

@Composable
internal fun uiStrings(): UiStrings {
    val context = LocalContext.current
    // Read configuration as Compose state. The immutable snapshot also gives asynchronous
    // formatting a locale-scoped context rather than a retained Application/Activity context.
    val configuration = Configuration(LocalConfiguration.current)
    return remember(context, configuration) {
        UiStrings(context.createConfigurationContext(configuration).resources)
    }
}

@Composable
internal fun AppLanguageSetting() {
    val strings = uiStrings()
    val context = LocalContext.current
    val manager = context.getSystemService(LocaleManager::class.java)
    // LocaleManager is the only persistence owner (minSdk 33). Empty means follow system.
    // No configChanges interception or manual restart: Android recreates the Activity.
    val selected = manager.applicationLocales.let { if (it.isEmpty) "" else it[0].language }
    Column {
        Text(strings.get(R.string.language_title))
        listOf("" to R.string.language_system, "zh" to R.string.language_zh, "en" to R.string.language_en).forEach { (tag, label) ->
            TextButton(onClick = { manager.applicationLocales = LocaleList.forLanguageTags(tag) }) {
                RadioButton(selected = selected == tag, onClick = null)
                Text(strings.get(label))
            }
        }
    }
}
