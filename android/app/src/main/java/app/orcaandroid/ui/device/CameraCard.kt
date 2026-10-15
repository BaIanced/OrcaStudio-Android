package app.orcaandroid.ui.device

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.net.BambuCamera
import app.orcaandroid.net.PrinterConnection
import kotlinx.coroutines.flow.catch

/**
 * Live view of a Bambu printer's camera over the LAN. It only connects while the view is switched on
 * and this card is on screen: the printer serves one stream at a time and the picture is large.
 */
@Composable
internal fun CameraCard(connection: PrinterConnection) {
    var live by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var failure by remember { mutableStateOf<BambuCamera.CameraException.Kind?>(null) }
    val context = LocalContext.current

    // Leaving the tab (or the card) leaves composition and cancels the stream.
    LaunchedEffect(live, connection.url, connection.apiKey) {
        if (!live) return@LaunchedEffect
        failure = null
        val host = connection.url.substringAfter("://").substringBefore('/').substringBefore(':')
        BambuCamera.frames(host, connection.apiKey)
            .catch { e ->
                failure = (e as? BambuCamera.CameraException)?.kind ?: BambuCamera.CameraException.Kind.BROKEN
                live = false
            }
            .collect { frame = it }
    }

    PanelCard(stringResource(R.string.device_camera)) {
        frame?.let { bmp ->
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height), contentScale = ContentScale.Fit)
        }
        failure?.let {
            Text(stringResource(when (it) {
                BambuCamera.CameraException.Kind.UNREACHABLE -> R.string.camera_unreachable
                BambuCamera.CameraException.Kind.REJECTED -> R.string.camera_rejected
                BambuCamera.CameraException.Kind.BROKEN -> R.string.camera_broken
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (frame == null && failure == null) Text(stringResource(R.string.camera_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { live = !live }) { Text(stringResource(if (live) R.string.camera_stop else R.string.camera_start)) }
            frame?.let { bmp ->
                OutlinedButton(onClick = {
                    val ok = runCatching { saveSnapshot(context, bmp) }.isSuccess
                    Toast.makeText(context, if (ok) R.string.camera_saved else R.string.camera_save_failed, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.camera_snapshot)) }
            }
        }
    }
}

private fun saveSnapshot(context: android.content.Context, bmp: Bitmap) {
    val name = "printer-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + ".jpg"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
    context.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
}
