package app.orcaandroid.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.orcaandroid.core.ThemeMode
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        requestNotificationPermission()
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            OrcaTheme(state.themeMode, state.dynamicColor) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { AppScaffold(state, vm) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Models / projects / G-code handed to the app via "open with" or "share". */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) lifecycleScope.launch {
            vm.awaitReady()
            vm.files.openModels(uris)
        }

        // Debug builds only: automated tests drive the app with intent extras, e.g.
        // adb shell am start -n de.cl1x.orca_android.debug/app.orcaandroid.ui.MainActivity
        //     --esa orca.printers "Qidi Q1 Pro|0.4" --es orca.model <path of a model> --ez orca.slice true
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val printers = intent.getStringArrayExtra("orca.printers")
        val model = intent.getStringExtra("orca.model")
        val slice = intent.getBooleanExtra("orca.slice", false)
        if (printers == null && model == null) return
        lifecycleScope.launch {
            if (printers != null) {
                vm.state.value.let { if (it.phase == Phase.LOADING) kotlinx.coroutines.delay(500) }
                while (vm.state.value.phase == Phase.LOADING || vm.state.value.vendors.isEmpty()) kotlinx.coroutines.delay(200)
                vm.presets.applyPrinterSelection(printers.toSet()).join()
            }
            vm.awaitReady()
            if (model != null) vm.files.openModels(listOf(Uri.fromFile(File(model))), append = false).join()
            if (slice) vm.slicing.slice()
        }
    }

    /** Notifications (Android 13+) and local network access to printers (Android 17+). */
    private fun requestNotificationPermission() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(LOCAL_NETWORK_PERMISSION)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }

    /** Hardware keyboard shortcuts, as on the desktop. */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val s = vm.state.value
        when {
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Z && event.isShiftPressed -> vm.scene.redo()
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Z -> vm.scene.undo()
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Y -> vm.scene.redo()
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_R -> vm.slicing.slice()
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_A -> vm.scene.arrange(allPlates = false)
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_1 -> vm.setScreen(Screen.PREPARE)
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_2 -> vm.setScreen(Screen.PREVIEW)
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_3 -> vm.setScreen(Screen.DEVICE)
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_N -> vm.files.newProject()
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_D && s.selection != null -> vm.scene.duplicate(1)
            else -> return super.onKeyShortcut(keyCode, event)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = vm.state.value
        // Single-key shortcuts act on the 3D view only, not while a settings editor is open.
        if (s.editor == null && s.screen == Screen.PREPARE) when (keyCode) {
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> if (s.selection != null) { vm.scene.deleteSelected(); return true }
            KeyEvent.KEYCODE_ESCAPE -> if (s.tool != Tool.None || s.selection != null) {
                vm.scene.setTool(Tool.None); vm.scene.select(null); return true
            }
            KeyEvent.KEYCODE_O -> if (s.selection != null && !event.isCtrlPressed) { vm.scene.autoOrient(); return true }
            KeyEvent.KEYCODE_F -> if (s.selection != null && !event.isCtrlPressed) { vm.scene.setTool(Tool.LayOnFace); return true }
        }
        return super.onKeyDown(keyCode, event)
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
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
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
