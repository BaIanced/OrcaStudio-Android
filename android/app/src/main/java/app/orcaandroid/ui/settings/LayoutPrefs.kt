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
        SwitchRow(stringResource(R.string.lab_plate_hd), state.workspace.plateHd, onChange = vm::setPlateHd)
    }
}

