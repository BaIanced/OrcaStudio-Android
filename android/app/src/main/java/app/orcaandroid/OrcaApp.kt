package app.orcaandroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.orcaandroid.core.AppSettings
import app.orcaandroid.core.Engine
import app.orcaandroid.core.ResourceStore
import app.orcaandroid.net.PrinterStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Progress of a running slice, shared with the foreground service that keeps it alive. */
data class SliceProgress(val percent: Int, val text: String)

/** App-wide singletons (manual dependency injection). */
class AppContainer(app: Application) {
    val settings = AppSettings(app)
    val resources = ResourceStore(app)
    val engine = Engine(app.cacheDir)
    /** Non-null while slicing; observed by SliceService. */
    val sliceProgress = MutableStateFlow<SliceProgress?>(null)
    /** Latest job status of the monitored printer; written by PrintMonitorService. */
    val printerStatus = MutableStateFlow<PrinterStatus?>(null)
    /** For work that must finish even when the activity and its view model are gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class OrcaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_SLICING, getString(R.string.channel_slicing), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_PRINT, getString(R.string.channel_print), NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    companion object {
        const val CHANNEL_SLICING = "slicing"
        const val CHANNEL_PRINT = "print"
    }
}

val Application.container get() = (this as OrcaApp).container
