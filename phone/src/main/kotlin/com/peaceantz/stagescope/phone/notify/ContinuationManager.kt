package com.peaceantz.stagescope.phone.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.peaceantz.stagescope.phone.MainActivity
import com.peaceantz.stagescope.phone.R
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.phone.link.Continuations
import com.peaceantz.stagescope.phone.link.WatchLink
import com.peaceantz.stagescope.phone.tools.ContinueTarget
import com.peaceantz.stagescope.shared.protocol.ContinueOutcome
import com.peaceantz.stagescope.shared.protocol.ContinueReply
import java.util.concurrent.ConcurrentHashMap

/**
 * "Continue on phone" without bypassing Android's background-activity restrictions. A message from
 * the watch cannot force an Activity to the front, so the phone posts a notification whose
 * PendingIntent opens the *exact saved item by its stored id* (the deep link carries only an opaque
 * id -- never any content). When the person actually opens it, [onOpened] tells the watch, which
 * only then may say "Opened on phone". If notifications are off, the item is still saved and listed
 * under Recent in the phone app, and the watch is told so honestly.
 */
class ContinuationManager(
    private val context: Context,
    private val data: PhoneData,
    private val link: WatchLink,
) : Continuations {

    /** target key -> the watch's request id, so [onOpened] can answer the right question. */
    private val waiting = ConcurrentHashMap<String, String>()

    override suspend fun continueOnPhone(requestId: String, target: ContinueTarget): ContinueOutcome {
        val key = keyOf(target) ?: return ContinueOutcome.NOT_FOUND
        val exists = target.actionId?.let { data.actions.get(it) != null } ?: target.conversationId?.let { data.conversations.get(it) != null } ?: false
        if (!exists) return ContinueOutcome.NOT_FOUND
        waiting[key] = requestId
        if (!notificationsEnabled()) return ContinueOutcome.SAVED_NOTIFICATIONS_DISABLED
        ensureChannel()
        val uri = Uri.parse("stagescope://task/" + (if (target.actionId != null) "action/${target.actionId}" else "conversation/${target.conversationId}"))
        val open = Intent(Intent.ACTION_VIEW, uri).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stagescope_mono)
            .setContentTitle("StageScope — continue from your watch")
            .setContentText(if (target.actionId != null) "Your draft is ready to review." else "Your conversation is ready to continue.")
            .setContentIntent(pi).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(key.hashCode(), notification)
            ContinueOutcome.NOTIFICATION_POSTED
        } catch (e: SecurityException) {
            ContinueOutcome.SAVED_NOTIFICATIONS_DISABLED
        }
    }

    /** Called by the phone UI when it really shows this item. */
    suspend fun onOpened(conversationId: String?, actionId: String?) {
        val key = keyOf(ContinueTarget(conversationId, actionId)) ?: return
        val requestId = waiting.remove(key) ?: return
        NotificationManagerCompat.from(context).cancel(key.hashCode())
        link.send(ContinueReply(requestId, ContinueOutcome.OPENED))
    }

    fun notificationsEnabled(): Boolean {
        val granted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun keyOf(t: ContinueTarget): String? = when {
        t.actionId != null -> "action:${t.actionId}"
        t.conversationId != null -> "conversation:${t.conversationId}"
        else -> null
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Continue from watch", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Opens the draft or conversation you sent from your watch."
            })
        }
    }

    companion object {
        const val CHANNEL_ID = "stagescope_continue"
    }
}
