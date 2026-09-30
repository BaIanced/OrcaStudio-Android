package app.orcaandroid.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.orcaandroid.OrcaApp
import app.orcaandroid.R
import app.orcaandroid.SliceProgress
import app.orcaandroid.container
import app.orcaandroid.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while the engine slices, so switching apps does not get a
 * long slice killed. The slicing itself runs in the app's engine thread; this service only mirrors
 * [app.orcaandroid.AppContainer.sliceProgress] into a notification and stops when it clears.
 */
class SliceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            application.container.engine.cancelSlicing()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(SliceProgress(0, "")),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        scope.launch {
            application.container.sliceProgress.collect { progress ->
                if (progress == null) {
                    ServiceCompat.stopForeground(this@SliceService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification(progress))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(p: SliceProgress): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, SliceService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, OrcaApp.CHANNEL_SLICING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.slicing))
            .setContentText(p.text)
            .setProgress(100, p.percent, p.percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.cancel), cancel)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL = "cancel"

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, SliceService::class.java))
    }
}
