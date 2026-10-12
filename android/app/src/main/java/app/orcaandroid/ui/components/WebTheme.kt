package app.orcaandroid.ui.components

import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance

/** Whether the app shows its dark theme (the app's own setting, or the system's). */
@Composable
fun appIsDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * A WebView in the app's light or dark theme. Pages see `prefers-color-scheme` from the theme
 * (isLightTheme), and on Android 13+ pages without a dark style of their own are darkened.
 */
fun themedWebView(context: Context, dark: Boolean): WebView {
    val theme = if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar
    return WebView(ContextThemeWrapper(context, theme)).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) settings.isAlgorithmicDarkeningAllowed = dark
    }
}
