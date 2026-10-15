package app.orcaandroid.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.orcaandroid.R
import app.orcaandroid.core.Translator

/**
 * Where a setting lives in this app (More > section > label). MoreScreen takes the label from here too, so
 * messages that point at a setting always show its current name; move the setting by changing [section] only.
 */
enum class SettingPath(val section: Int, val label: Int) {
    MIXED_TEMPS(R.string.printers_and_presets, R.string.allow_mixed_temps);

    @Composable
    fun text(): String = "${stringResource(R.string.more)} > ${stringResource(section)} > ${stringResource(label)}"
}

/** OrcaSlicer's sentence that sends the user to its Preferences dialog (Print.cpp, check_multi_filament_valid). */
private const val UPSTREAM_MIXED_TEMPS_HINT =
    "If you still want to print, you can enable the option in Preferences / Control / Slicing / Remove mixed temperature restriction."

/**
 * An engine error as shown to the user: translated, with the pointers to OrcaSlicer's desktop Preferences
 * replaced by this app's own setting location.
 */
@Composable
fun engineErrorText(message: String, translator: Translator): String {
    if (UPSTREAM_MIXED_TEMPS_HINT !in message) return translator.tr(message)
    val head = translator.tr(message.substringBefore(UPSTREAM_MIXED_TEMPS_HINT).trim())
    val tail = message.substringAfter(UPSTREAM_MIXED_TEMPS_HINT).trim().let { if (it.isEmpty()) "" else " " + translator.tr(it) }
    return head + " " + stringResource(R.string.mixed_temps_hint, SettingPath.MIXED_TEMPS.text()) + tail
}
