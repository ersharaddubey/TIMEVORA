package com.timevora.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.appwidget.AppWidgetProvider

/**
 * Large (4x4) home-screen widget, and the router for every refresh request.
 * Whichever timevora widget is placed, a refresh redraws all of them with the current theme.
 */
open class timevoraWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) WidgetUpdater.updateAll(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.timevora.app.widget.REFRESH"

        /** Safe to call from anywhere: sends a broadcast, the receiver redraws every placed widget. */
        fun requestRefresh(context: Context) {
            val intent = Intent(context, timevoraWidgetProvider::class.java).setAction(ACTION_REFRESH)
            context.sendBroadcast(intent)
        }
    }
}

/** Small (2x2): today's focus time, XP and habits. */
class SmallWidgetProvider : timevoraWidgetProvider()

/** Medium (4x2): today's numbers plus Task, Note and Voice buttons. */
class MediumWidgetProvider : timevoraWidgetProvider()

/** Focus timer (4x2): start a session in one tap, then a live countdown. */
class FocusTimerWidgetProvider : timevoraWidgetProvider()
