package com.beacon.testapp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

class ClosestBeaconWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val state = BeaconTracker.peek()?.uiState?.value ?: ScanUiState()
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, state))
        }
    }

    companion object {
        fun updateAll(context: Context, state: ScanUiState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ClosestBeaconWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            val views = buildRemoteViews(context, state)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun buildRemoteViews(context: Context, state: ScanUiState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.closest_beacon_widget)
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            val closest = state.closestBeacon
            val floorCode = state.selectedFloor ?: closest?.let { BeaconDisplayFormat.floorCode(it) }
            val label = BeaconDisplayFormat.floorLabelFromCode(floorCode)
                ?: if (closest == null) {
                    context.getString(R.string.widget_searching)
                } else {
                    BeaconDisplayFormat.widgetLabel(closest)
                }
            val backgroundRes = when (floorCode) {
                "B1" -> R.drawable.widget_background_b1
                "B2" -> R.drawable.widget_background_b2
                "1F" -> R.drawable.widget_background_1f
                else -> R.drawable.widget_background
            }
            val textColor = BeaconDisplayFormat.floorTextColorArgb(floorCode)
            val receivedAt = if (state.isInMeasureRange) {
                state.lastUpdateEpochMs ?: state.closestApproach?.epochMs
            } else {
                state.closestApproach?.epochMs
            }
            views.setInt(R.id.widget_root, "setBackgroundResource", backgroundRes)
            views.setTextViewText(R.id.widget_name, label)
            views.setTextColor(R.id.widget_name, textColor)
            if (receivedAt != null && closest != null) {
                views.setViewVisibility(R.id.widget_received_at, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_received_at,
                    BeaconDisplayFormat.koreanDateTimeWidget(receivedAt),
                )
                views.setTextColor(R.id.widget_received_at, textColor)
            } else {
                views.setViewVisibility(R.id.widget_received_at, View.GONE)
            }
            return views
        }
    }
}
