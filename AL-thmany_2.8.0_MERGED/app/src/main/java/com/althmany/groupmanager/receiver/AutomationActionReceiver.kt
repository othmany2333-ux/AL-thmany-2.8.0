package com.althmany.groupmanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.althmany.groupmanager.GroupManagerApp
import com.althmany.groupmanager.domain.AutomationStopReason
import com.althmany.groupmanager.domain.SessionRules
import com.althmany.groupmanager.model.AutomationBackend
import com.althmany.groupmanager.util.GroupJoinerResultStore
import com.althmany.groupmanager.util.QuickJoinNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles pause/resume/stop from the ongoing notification without opening the UI. */
class AutomationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as GroupManagerApp
                when (intent?.action) {
                    QuickJoinNotification.ACTION_STOP_AUTOMATION -> {
                        app.preferences.stopAccessibilityBatch(
                            AutomationStopReason.USER_STOPPED,
                            "Stopped from notification"
                        )
                        GroupJoinerResultStore.sync(context, app.repository.loadActiveSnapshot())
                        QuickJoinNotification.cancel(context)
                    }


                    QuickJoinNotification.ACTION_CANCEL_SCHEDULE -> {
                        app.preferences.clearScheduledStart()
                        app.preferences.resetAccessibilityRun(app.preferences.activeSessionId)
                        QuickJoinNotification.cancel(context)
                    }

                    QuickJoinNotification.ACTION_TOGGLE_PAUSE_AUTOMATION -> {
                        if (!app.preferences.accessibilityBatchRunning) {
                            QuickJoinNotification.cancel(context)
                            return@launch
                        }
                        if (app.preferences.accessibilityPaused) {
                            app.preferences.resumeAccessibilityBatch()
                        } else {
                            app.preferences.pauseAccessibilityBatch()
                        }
                        val snapshot = app.repository.loadActiveSnapshot()
                        val current = snapshot?.links?.let {
                            SessionRules.currentOpened(it) ?: SessionRules.nextActionable(it)
                        }
                        if (app.preferences.runtimeAutomationBackend == AutomationBackend.ACCESSIBILITY) {
                            QuickJoinNotification.showAutomation(
                                context = context,
                                processedInBatch = app.preferences.accessibilityProcessedCount,
                                currentLinkNumber = current?.position?.plus(1),
                                totalLinks = snapshot?.stats?.total ?: 0,
                                delaySeconds = app.preferences.accessibilityJoinDelaySeconds,
                                paused = app.preferences.accessibilityPaused
                            )
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
