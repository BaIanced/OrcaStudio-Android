package app.orcaandroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Slider
import app.orcaandroid.core.PlateSwitcher
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.ViewDefaults
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.prepare.SwitchRow
import kotlin.math.roundToInt

/**
 * The layout settings that replaced the Layout lab: Interface size (85 to 120 %, scales the whole interface; the tool
 * buttons stay at 40 dp or more), the plate switcher over the 3D view and detailed plates. Shown under More > Appearance
 * and in the design panel's Layout tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayoutPrefs(state: UiState, vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.interface_size), style = MaterialTheme.typography.bodyMedium)
        val sizes = ViewDefaults.INTERFACE_SIZES
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            sizes.forEachIndexed { i, size ->
                SegmentedButton(state.chrome.interfaceSize == size, { vm.setInterfaceSize(size) }, SegmentedButtonDefaults.itemShape(i, sizes.size)) {
                    Text("${(size * 100).roundToInt()} %", maxLines = 1)
                }
            }
        }
        SwitchRow(stringResource(R.string.lab_show_plates), state.chrome.showPlates, onChange = vm::setShowPlates)
        Text(stringResource(R.string.lab_plate_switcher), style = MaterialTheme.typography.bodyMedium)
        val styles = listOf(PlateSwitcher.TABS to R.string.lab_tabs, PlateSwitcher.FILMSTRIP to R.string.lab_filmstrip)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            styles.forEachIndexed { i, (style, label) ->
                SegmentedButton(state.chrome.plates == style, { vm.setPlates(style) }, SegmentedButtonDefaults.itemShape(i, styles.size)) { Text(stringResource(label)) }
            }
        }
        SwitchRow(stringResource(R.string.lab_plates_on_handle), state.chrome.platesOnHandle, onChange = vm::setPlatesOnHandle)
        SwitchRow(stringResource(R.string.lab_tools_left), state.chrome.toolsLeft, onChange = vm::setToolsLeft)
        SwitchRow(stringResource(R.string.lab_fullscreen_portrait), state.chrome.fullscreenPortrait, onChange = vm::setFullscreenPortrait)
        SwitchRow(stringResource(R.string.lab_plate_hd), state.workspace.plateHd, onChange = vm::setPlateHd)
        PercentSlider(R.string.lab_plate_hd_under, state.workspace.plateUnderAlpha, ViewDefaults.PLATE_UNDER_ALPHAS, false, vm::setPlateUnderAlpha)
        PercentSlider(R.string.lab_overlay_alpha, state.chrome.overlayAlpha, ViewDefaults.OVERLAY_ALPHAS, false, vm::setOverlayAlpha)
        Text(stringResource(R.string.lab_scale_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PercentSlider(R.string.lab_text_scale, state.chrome.textScale, ViewDefaults.TEXT_SCALES, true, vm::setTextScale)
        PercentSlider(R.string.lab_plate_scale, state.chrome.plateScale, ViewDefaults.PLATE_SCALES, true, vm::setPlateScale)
        PercentSlider(R.string.lab_slice_scale, state.chrome.sliceScale, ViewDefaults.SLICE_SCALES, true, vm::setSliceScale)
        PercentSlider(R.string.lab_overlay_scale, state.chrome.overlayScale, ViewDefaults.OVERLAY_SCALES, true, vm::setOverlayScale)
    }
}

/** A plain slider whose label shows the value in %; with [onRelease] the value is applied when the finger lifts (sizes would move under it). */
@Composable
private fun PercentSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, onRelease: Boolean, onSet: (Float) -> Unit) {
    var live by remember(value) { mutableFloatStateOf(value) }
    Text(stringResource(label, (live * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
    if (onRelease) Slider(live, { live = it }, valueRange = range, onValueChangeFinished = { onSet((live * 20).roundToInt() / 20f) })
    else Slider(value, onSet, valueRange = range)
}

