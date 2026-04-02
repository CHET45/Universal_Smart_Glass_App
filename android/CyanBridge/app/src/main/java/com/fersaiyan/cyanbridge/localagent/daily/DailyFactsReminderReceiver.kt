package com.fersaiyan.cyanbridge.localagent.daily

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.ui.ChatThreadActivity

class DailyFactsReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_REMIND) return

        ensureChannel(context)

        // Open a new chat to review daily facts with the agent.
        LocalAgentMemoryStore.ensureSeedFiles(context)

        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))
        val retentionDays = MemoryModeManager.getScreenOcrRetentionDays(context)

        val openIntent = Intent(context, ChatThreadActivity::class.java)
            .putExtra(ChatThreadActivity.EXTRA_CREATE_THREAD_TITLE, "Daily facts review")
            .putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_REVIEW, true)
            .putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_DATE, date)
            .putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_LOOKBACK_DAYS, retentionDays)

        val openPi = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Daily facts")
            .setContentText("Quick check: verify today’s facts for your Local Agent memory")
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily facts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Daily prompts to verify Local Agent facts"
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_REMIND = "com.fersaiyan.cyanbridge.action.DAILY_FACTS_REMIND"

        private const val CHANNEL_ID = "daily_facts"
        private const val NOTIF_ID = 44102
    }
}
