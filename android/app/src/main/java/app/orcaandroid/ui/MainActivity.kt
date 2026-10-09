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
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.components.framePlate
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        requestNotificationPermission()
        PlateView.onKey = ::viewKey
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            OrcaTheme(state.themeMode, state.dynamicColor) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { AppScaffold(state, vm) }
                }
            }
        }
    }

    /** Android may end the process any time after this; keep the state for the next start. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.saveSession()
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

    /** The 3D view has keyboard focus (it takes it on touch); the desktop's canvas shortcuts need it. */
    private val viewFocused get() = PlateView.active?.get()?.hasFocus() == true

    /** Hardware keyboard shortcuts with Ctrl, as on the desktop (Shortcuts.cpp defaults). */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val s = vm.state.value
        val view = PlateView.active?.get()
        // Selection and camera shortcuts only while the 3D view has focus, so Ctrl+A/C/V keep
        // working in text fields.
        val onPlate = viewFocused && s.editor == null && s.screen == Screen.PREPARE
        val onView = viewFocused && s.editor == null && (s.screen == Screen.PREPARE || s.screen == Screen.PREVIEW)
        val shift = event.isShiftPressed
        if (!event.isCtrlPressed) return super.onKeyShortcut(keyCode, event)
        when {
            keyCode == KeyEvent.KEYCODE_Z && shift -> vm.scene.redo()
            keyCode == KeyEvent.KEYCODE_Z -> vm.scene.undo()
            keyCode == KeyEvent.KEYCODE_Y -> vm.scene.redo()
            keyCode == KeyEvent.KEYCODE_R -> vm.slicing.slice()
            keyCode == KeyEvent.KEYCODE_N -> vm.files.newProject()
            onPlate && keyCode == KeyEvent.KEYCODE_A && shift -> vm.scene.selectAllPlates()
            onPlate && keyCode == KeyEvent.KEYCODE_A -> vm.scene.selectAll()
            onPlate && keyCode == KeyEvent.KEYCODE_C -> vm.scene.copySelected()
            onPlate && keyCode == KeyEvent.KEYCODE_V -> vm.scene.paste()
            onPlate && keyCode == KeyEvent.KEYCODE_K -> vm.scene.duplicate(1)
            onPlate && keyCode == KeyEvent.KEYCODE_D -> vm.scene.deleteAll()
            // Camera views: 0 default, 1 top, 2 bottom, 3 front, 4 rear, 5 left, 6 right, 7 plate.
            onView && view != null && keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_7 -> when (val n = keyCode - KeyEvent.KEYCODE_0) {
                7 -> view.framePlate(s.activePlate)
                else -> view.camera.preset(listOf(0, 1, 5, 2, 6, 3, 4)[n])
            }
            else -> return super.onKeyShortcut(keyCode, event)
        }
        return true
    }

    // Keys nothing else used (no text field or button took them).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = viewKey(keyCode, event) || super.onKeyDown(keyCode, event)

    /** Single-key shortcuts of the 3D view, as on the desktop; the view forwards its keys here first. */
    private fun viewKey(keyCode: Int, event: KeyEvent): Boolean {
        val s = vm.state.value
        if (s.editor != null || event.isCtrlPressed) return false
        if (keyCode == KeyEvent.KEYCODE_TAB && (s.screen == Screen.PREPARE || s.screen == Screen.PREVIEW)) {
            vm.setScreen(if (s.screen == Screen.PREPARE) Screen.PREVIEW else Screen.PREPARE)
            return true
        }
        val view = PlateView.active?.get()
        when (keyCode) {
            KeyEvent.KEYCODE_I -> { view?.camera?.zoom(1.15f); return true }
            KeyEvent.KEYCODE_O -> { view?.camera?.zoom(1 / 1.15f); return true }
        }
        return when (s.screen) {
            Screen.PREVIEW -> previewKey(keyCode, s)
            Screen.PREPARE -> prepareKey(keyCode, event, s)
            else -> false
        }
    }

    /** Single-key shortcuts of the Prepare 3D view, as on the desktop. */
    private fun prepareKey(keyCode: Int, event: KeyEvent, s: UiState): Boolean {
        val any = s.selectedItems.isNotEmpty()
        val single = s.selection != null
        val step = if (event.isShiftPressed) 1f else 10f
        fun paint(kind: String, state: Int) = if (single) vm.scene.setTool(Tool.Paint(kind, state, 3f)) else null
        when (keyCode) {
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> if (any) vm.scene.deleteSelected() else return false
            KeyEvent.KEYCODE_ESCAPE -> if (s.tool != Tool.None || any || s.selectMode) {
                vm.scene.setTool(Tool.None); vm.scene.select(null); vm.scene.setSelectMode(false)
            } else return false
            KeyEvent.KEYCODE_A -> vm.scene.arrange(allPlates = !event.isShiftPressed)
            KeyEvent.KEYCODE_Q -> vm.scene.autoOrient()
            KeyEvent.KEYCODE_F -> if (single) vm.scene.setTool(Tool.LayOnFace) else return false
            KeyEvent.KEYCODE_C -> if (single) vm.scene.startCut() else return false
            KeyEvent.KEYCODE_L -> paint("support", 1) ?: return false
            KeyEvent.KEYCODE_P -> paint("seam", 1) ?: return false
            KeyEvent.KEYCODE_H -> paint("fuzzy", 1) ?: return false
            KeyEvent.KEYCODE_N -> paint("color", 2) ?: return false
            KeyEvent.KEYCODE_U -> vm.scene.setTool(Tool.Measure)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (any) vm.scene.nudge(-step, 0f) else return false
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (any) vm.scene.nudge(step, 0f) else return false
            KeyEvent.KEYCODE_DPAD_UP -> if (any) vm.scene.nudge(0f, step) else return false
            KeyEvent.KEYCODE_DPAD_DOWN -> if (any) vm.scene.nudge(0f, -step) else return false
            KeyEvent.KEYCODE_PAGE_UP -> if (single) vm.scene.rotateSelected(45f) else return false
            KeyEvent.KEYCODE_PAGE_DOWN -> if (single) vm.scene.rotateSelected(-45f) else return false
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> if (any) vm.scene.duplicate(1) else return false
            KeyEvent.KEYCODE_EQUALS -> if (any && event.isShiftPressed) vm.scene.duplicate(1) else return false
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> if (single) vm.scene.removeCopy() else return false
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                // Sets the filament of the selected objects.
                val n = keyCode - KeyEvent.KEYCODE_0
                if (any && n <= s.filaments.size) vm.scene.setSelectedObjectsSetting("extruder", n.toString()) else return false
            }
            else -> return false
        }
        return true
    }

    /** Preview: up / down step the layer, left / right the moves in it, Home / End the moves' ends. */
    private fun previewKey(keyCode: Int, s: UiState): Boolean {
        val layers = s.shownResult?.layers ?: return false
        if (layers.isEmpty()) return false
        val p = s.preview
        val last = layers.lastIndex
        val hi = p.layerHigh.coerceIn(0, last)
        val moves = (layers.getOrNull(hi + 1)?.extrusion ?: s.shownResult!!.extrusionCount) - layers[hi].extrusion
        val move = p.moveEnd ?: moves
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> vm.slicing.updatePreview { it.copy(layerHigh = (hi + 1).coerceAtMost(last), moveEnd = null) }
            KeyEvent.KEYCODE_DPAD_DOWN -> vm.slicing.updatePreview { it.copy(layerHigh = (hi - 1).coerceAtLeast(p.layerLow.coerceIn(0, last)), moveEnd = null) }
            KeyEvent.KEYCODE_DPAD_LEFT -> vm.slicing.updatePreview { it.copy(moveEnd = (move - 1).coerceAtLeast(1)) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> vm.slicing.updatePreview { it.copy(moveEnd = (move + 1).takeIf { m -> m < moves }) }
            KeyEvent.KEYCODE_MOVE_HOME -> vm.slicing.updatePreview { it.copy(moveEnd = 1) }
            KeyEvent.KEYCODE_MOVE_END -> vm.slicing.updatePreview { it.copy(moveEnd = null) }
            else -> return false
        }
        return true
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
