package com.lumi.pet

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Timer
import java.util.TimerTask

class UsageTracker(
    private val context: Context,
    private val onChanged: (String) -> Unit
) {
    private var timer: Timer? = null
    private var lastApp: String = ""

    fun start() {
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val current = getForegroundApp()
                if (current.isNotEmpty() && current != lastApp) {
                    lastApp = current
                    onChanged(current)
                }
            }
        }, 0, 3000)
    }

    private fun getForegroundApp(): String {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 5000, now)
            val event = UsageEvents.Event()
            var fg = ""
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    fg = event.packageName
                }
            }
            fg
        } catch (_: Exception) { "" }
    }

    fun stop() {
        timer?.cancel()
        timer = null
    }
}