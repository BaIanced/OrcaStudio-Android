package com.orcaslicer.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.orcaslicer.android.BuildConfig
import com.orcaslicer.android.core.ThemeMode

/** App-wide preferences and the about/licenses section. */
@Composable
fun AppSettingsScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("App-Einstellungen", style = MaterialTheme.typography.headlineSmall)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Darstellung", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Design", Modifier.weight(1f))
                        SingleChoiceSegmentedButtonRow {
                            val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Hell", ThemeMode.DARK to "Dunkel")
                            modes.forEachIndexed { i, (mode, label) ->
                                SegmentedButton(state.themeMode == mode, { vm.setThemeMode(mode) }, SegmentedButtonDefaults.itemShape(i, modes.size)) {
                                    Text(label)
                                }
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Farben vom Hintergrundbild")
                                Text("Material You (Android 12+)", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = state.dynamicColor, onCheckedChange = vm::setDynamicColor)
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Drucker", style = MaterialTheme.typography.titleMedium)
                    Text("Drucker und Düsen auswählen, deren Profile installiert werden.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = vm::openVendorSetup) { Text("Drucker verwalten …") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Über", style = MaterialTheme.typography.titleMedium)
                    Text("OrcaSlicer für Android ${BuildConfig.VERSION_NAME}")
                    Text(
                        "Slicing-Kern: OrcaSlicer ${BuildConfig.ORCA_VERSION} (${BuildConfig.ORCA_COMMIT.take(10)})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Inoffizielle Android-Portierung von OrcaSlicer. Nicht mit dem OrcaSlicer-Projekt, SoftFever " +
                            "oder Druckerherstellern verbunden.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider()
                    Text(
                        "Diese App ist freie Software unter der GNU Affero General Public License v3.0. Sie enthält " +
                            "OrcaSlicer (AGPL-3.0), das auf Bambu Studio, PrusaSlicer und Slic3r aufbaut, sowie " +
                            "Bibliotheken wie Boost, CGAL, OpenCASCADE, OpenCV, oneTBB, OpenVDB und weitere – siehe " +
                            "THIRD_PARTY_NOTICES im Quellcode.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.SOURCE_URL)))
                        }) { Text("Quellcode") }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gnu.org/licenses/agpl-3.0.html")))
                        }) { Text("Lizenz (AGPL-3.0)") }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SoftFever/OrcaSlicer")))
                        }) { Text("OrcaSlicer") }
                    }
                }
            }
        }
    }
}
