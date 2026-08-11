package com.althmany.groupmanager.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.althmany.groupmanager.R
import com.althmany.groupmanager.domain.AutomationPolicy
import com.althmany.groupmanager.receiver.AutomationActionReceiver
import com.althmany.groupmanager.ui.MainActivity
import java.util.Date

object QuickJoinNotification {
    const val ACTION_JOINED_NEXT = "com.althmany.groupmanager.action.JOINED_NEXT"
    const val ACTION_SKIP_NEXT = "com.althmany.groupmanager.action.SKIP_NEXT"
    const val ACTION_REOPEN = "com.althmany.groupmanager.action.REOPEN"
    const val ACTION_STOP_AUTOMATION =
        "com.althmany.groupmanager.action.STOP_AUTOMATION"
    const val ACTION_TOGGLE_PAUSE_AUTOMATION =
        "com.althmany.groupmanager.action.TOGGLE_PAUSE_AUTOMATION"
    const val ACTION_CANCEL_SCHEDULE =
        "com.althmany.groupmanager.action.CANCEL_SCHEDULE"

    private const val CHANNEL_ID = "runtime_controls_v2"
    private const val NOTIFICATION_ID = 5201

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.quick_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.quick_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Manual-mode notification actions. */
    fun show(context: Context, linkNumber: Int?, total: Int?) {
        if (!canPost(context)) return
        createChannel(context)

        val contentIntent = actionIntent(context, Intent.ACTION_MAIN, 5200)
        val joinedIntent = actionIntent(context, ACTION_JOINED_NEXT, 5201)
        val skipIntent = actionIntent(context, ACTION_SKIP_NEXT, 5202)
        val reopenIntent = actionIntent(context, ACTION_REOPEN, 5203)

        val progress = if (linkNumber != null && total != null) {
            context.getString(R.string.quick_notification_progress, linkNumber, total)
        } else {
            context.getString(R.string.quick_notification_body)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(context.getString(R.string.quick_notification_title))
            .setContentText(progress)
            .setStyle(NotificationCompat.BigTextStyle().bigText(progress))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, context.getString(R.string.quick_action_joined), joinedIntent)
            .addAction(0, context.getString(R.string.quick_action_skip), skipIntent)
            .addAction(0, context.getString(R.string.quick_action_reopen), reopenIntent)
            .build()

        notifySafely(context, notification)
    }

    /** Ongoing notification while the current bounded explicit run is active. */
    fun showAutomation(
        context: Context,
        processedInBatch: Int,
        currentLinkNumber: Int?,
        totalLinks: Int,
        delaySeconds: Int,
        paused: Boolean
    ) {
        if (!canPost(context)) return
        createChannel(context)

        val safeProcessed = processedInBatch.coerceIn(0, AutomationPolicy.BATCH_SIZE)
        val safeDelay = AutomationPolicy.clampDelaySeconds(delaySeconds)
        val body = if (paused) {
            context.getString(
                R.string.automation_notification_paused,
                safeProcessed,
                AutomationPolicy.BATCH_SIZE
            )
        } else if (currentLinkNumber != null) {
            context.getString(
                R.string.automation_notification_progress,
                safeProcessed,
                AutomationPolicy.BATCH_SIZE,
                currentLinkNumber,
                totalLinks,
                safeDelay
            )
        } else {
            context.getString(
                R.string.automation_notification_waiting,
                safeProcessed,
                AutomationPolicy.BATCH_SIZE,
                safeDelay
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(context.getString(R.string.automation_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(actionIntent(context, Intent.ACTION_MAIN, 5210))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setCategory(android.app.Notification.CATEGORY_PROGRESS)
            .setProgress(AutomationPolicy.BATCH_SIZE, safeProcessed, false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                0,
                context.getString(
                    if (paused) R.string.automation_action_resume
                    else R.string.automation_action_pause
                ),
                controlIntent(context, ACTION_TOGGLE_PAUSE_AUTOMATION, 5211)
            )
            .addAction(
                0,
                context.getString(R.string.automation_action_stop),
                controlIntent(context, ACTION_STOP_AUTOMATION, 5212)
            )
            .build()

        notifySafely(context, notification)
    }

    fun showScheduled(
        context: Context,
        startAtMillis: Long,
        delaySeconds: Int
    ) {
        if (!canPost(context)) return
        createChannel(context)
        val timeText = DateFormat.getTimeFormat(context).format(Date(startAtMillis))
        val body = context.getString(
            R.string.automation_notification_scheduled_body,
            timeText,
            AutomationPolicy.clampDelaySeconds(delaySeconds)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(context.getString(R.string.automation_notification_scheduled_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(actionIntent(context, Intent.ACTION_MAIN, 5220))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(
                0,
                context.getString(R.string.automation_action_cancel_schedule),
                controlIntent(context, ACTION_CANCEL_SCHEDULE, 5221)
            )
            .build()
        notifySafely(context, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun notifySafely(context: Context, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission may be revoked between the permission check and notify().
        }
    }

    private fun controlIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, AutomationActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
}
