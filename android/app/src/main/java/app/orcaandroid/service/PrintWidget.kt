package app.orcaandroid.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import app.orcaandroid.R
import app.orcaandroid.net.PrinterStatus
import app.orcaandroid.ui.MainActivity

/**
 * Home-screen widget with the running print's progress. The print monitor pushes every poll through
 * [publish]; the last status is kept in preferences so the widget still renders when the app process is gone.
 */
class PrintWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context)) }
    }

    companion object {
        private const val PREFS = "print_widget"

        /** Remembers [status] (null = nothing to show) and redraws every placed widget. */
        fun publish(context: Context, printer: String?, status: PrinterStatus?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (status == null) clear() else {
                    putString("title", status.file ?: printer)
                    putString("state", status.state.name)
                    putFloat("progress", status.progress ?: -1f)
                    putLong("remaining", status.remainingSeconds ?: -1L)
                    putString("alert", status.alerts.firstOrNull()?.let { it.text ?: it.display })
                    putLong("at", System.currentTimeMillis())
                }
            }.apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PrintWidget::class.java))
            ids.forEach { manager.updateAppWidget(it, build(context)) }
        }

        private fun build(context: Context): RemoteViews {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val views = RemoteViews(context.packageName, R.layout.print_widget)
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            val state = p.getString("state", null)?.let { runCatching { PrinterStatus.State.valueOf(it) }.getOrNull() }
            if (state == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_detail, context.getString(R.string.widget_idle))
                views.setViewVisibility(R.id.widget_progress, View.GONE)
                return views
            }
            val progress = p.getFloat("progress", -1f)
            val remaining = p.getLong("remaining", -1L)
            val label = context.getString(when (state) {
                PrinterStatus.State.PRINTING -> R.string.state_printing
                PrinterStatus.State.PAUSED -> R.string.state_paused
                PrinterStatus.State.FINISHED -> R.string.state_finished
                PrinterStatus.State.STOPPED -> R.string.state_stopped
                PrinterStatus.State.ERROR -> R.string.state_error
                PrinterStatus.State.OFFLINE -> R.string.state_offline
                PrinterStatus.State.IDLE -> R.string.state_idle
            })
            val detail = buildList {
                add(label)
                if (progress >= 0f) add("${(progress * 100).toInt()} %")
                if (remaining >= 0 && state == PrinterStatus.State.PRINTING) {
                    add(context.getString(R.string.remaining_fmt, PrintMonitorService.formatDuration(remaining.toDouble())))
                }
                p.getString("alert", null)?.let(::add)
            }.joinToString(" · ")
            views.setTextViewText(R.id.widget_title, p.getString("title", null) ?: context.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_detail, detail)
            val showBar = progress >= 0f && state != PrinterStatus.State.OFFLINE
            views.setViewVisibility(R.id.widget_progress, if (showBar) View.VISIBLE else View.GONE)
            if (showBar) views.setProgressBar(R.id.widget_progress, 100, (progress * 100).toInt(), false)
            return views
        }
    }
}
