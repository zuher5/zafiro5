package com.niki914.zafiro.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.app.R
import com.niki914.zafiro.business.application.ApplicationService

class ZafiraSearchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_search_bar)

            // Search Box tap -> Open MainActivity and focus composer
            val searchIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(ApplicationService.EXTRA_FOCUS_COMPOSER, true)
            }
            val searchPendingIntent = PendingIntent.getActivity(
                context,
                1001,
                searchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_search_box, searchPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_search_icon, searchPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_search_text, searchPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_root, searchPendingIntent)

            // Action / Run Button tap -> Open MainActivity, start New Conversation and focus composer
            val actionIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(
                    ApplicationService.EXTRA_TARGET_CONVERSATION_ID,
                    ApplicationService.CONVERSATION_ID_NEW
                )
                putExtra(ApplicationService.EXTRA_FOCUS_COMPOSER, true)
            }
            val actionPendingIntent = PendingIntent.getActivity(
                context,
                1002,
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_action_button, actionPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
