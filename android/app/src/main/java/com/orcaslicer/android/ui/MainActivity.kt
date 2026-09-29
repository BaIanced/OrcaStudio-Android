package com.orcaslicer.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.orcaslicer.android.core.ThemeMode
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            OrcaTheme(state.themeMode, state.dynamicColor) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { App(state, vm) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Opens models passed via "open with"/"share", and the debug-only test hook. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.openModels(uris)

        // adb shell am start -n com.orcaslicer.android/.ui.MainActivity --es orca.model <path> [--ez orca.slice true]
        //     [--esa orca.printers "<model>|<nozzle>,..."]
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) intent.getStringArrayExtra("orca.printers")?.let { vm.debugSelectPrinters(it.toSet()) }
        val debugModel = intent.getStringExtra("orca.model")
        if (debuggable && debugModel != null) {
            vm.openModels(listOf(Uri.fromFile(File(debugModel))), autoSlice = intent.getBooleanExtra("orca.slice", false))
        }
    }
}

@Composable
private fun App(state: UiState, vm: MainViewModel) {
    when {
        state.initializing -> Busy(state.busyMessage ?: "OrcaSlicer wird vorbereitet …")
        state.needsVendorSetup -> PrinterSetupScreen(
            vendors = state.vendors,
            selected = state.selectedPrinters,
            canCancel = state.printers.isNotEmpty() && state.selectedPrinters.isNotEmpty(),
            onApply = vm::applyPrinterSelection,
            onCancel = vm::closeVendorSetup,
        )
        state.settingsType != null -> SettingsScreen(state, vm)
        else -> MainScaffold(state, vm)
    }
    if (!state.initializing && state.busyMessage != null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(state.busyMessage)
                }
            },
        )
    }
    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text("Fehler") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
        )
    }
}

private data class Destination(val screen: Screen, val label: String, val icon: ImageVector)

private val DESTINATIONS = listOf(
    Destination(Screen.PREPARE, "Vorbereiten", Icons.Default.Build),
    Destination(Screen.DEVICE, "Gerät", Icons.Default.PlayArrow),
    Destination(Screen.SETTINGS, "Einstellungen", Icons.Default.Settings),
)

/** Top-level navigation: rail on tablets, bottom bar on narrow screens. */
@Composable
private fun MainScaffold(state: UiState, vm: MainViewModel) {
    val content: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            when (state.screen) {
                Screen.PREPARE -> SlicerScreen(state, vm)
                Screen.DEVICE -> DeviceScreen(state, vm)
                Screen.SETTINGS -> AppSettingsScreen(state, vm)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    DESTINATIONS.forEach { d ->
                        NavigationRailItem(
                            selected = state.screen == d.screen,
                            onClick = { vm.setScreen(d.screen) },
                            icon = { Icon(d.icon, d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
                content(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f).fillMaxWidth())
                NavigationBar {
                    DESTINATIONS.forEach { d ->
                        NavigationBarItem(
                            selected = state.screen == d.screen,
                            onClick = { vm.setScreen(d.screen) },
                            icon = { Icon(d.icon, d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Busy(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(message)
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF00796B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    tertiary = Color(0xFFB26A00),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB1CCC6),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF121416),
    surface = Color(0xFF121416),
)

@Composable
fun OrcaTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    // Status/navigation bar icons must contrast with the app's (not the system's) theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
