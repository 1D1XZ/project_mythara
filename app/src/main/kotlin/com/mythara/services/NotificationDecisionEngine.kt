package com.mythara.services

import android.content.Context
import android.service.notification.NotificationListenerService
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides what to do with each newly-arrived notification based on
 * the user's learned dismissal patterns ([NotificationActionStore]).
 *
 * Today's policy is straightforward:
 *  - if [NotificationActionStore.shouldAutoDismiss] returns true
 *    for the package (i.e. user has repeatedly dismissed similar
 *    things), DISMISS — cancel the notification via the listener
 *    service and log it so the agent can surface "you also missed
 *    a Slack ping" if the user asks.
 *  - otherwise, ANNOUNCE — pass through to the existing
 *    notification-auto-process path that fires the agent loop.
 *
 * Future passes:
 *  - REPLY: for messaging-app notifications (WhatsApp, Messages,
 *    Email), use NotificationAction.RemoteInput to send a context-
 *    aware auto-reply. Requires careful safety (don't auto-reply
 *    to strangers, don't echo sensitive things).
 *  - Per-app pause windows ("never auto-dismiss Bank of America")
 */
@Singleton
class NotificationDecisionEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val actionStore: NotificationActionStore,
) {
    enum class Decision { Announce, AutoDismiss }

    suspend fun decide(notif: NotificationListener.Recent): Decision {
        if (!actionStore.isEnabled()) return Decision.Announce
        // Never auto-dismiss our own. (Already filtered upstream
        // but defense in depth — our FGS / QuickTalk notifs going
        // away is a UX disaster.)
        if (notif.packageName == ctx.packageName) return Decision.Announce
        if (actionStore.shouldAutoDismiss(notif.packageName)) {
            return Decision.AutoDismiss
        }
        return Decision.Announce
    }

    /**
     * Carry out the dismiss decision: tell the OS-bound listener
     * to cancel the notification + log it for the agent. Returns
     * true if the cancellation went through.
     */
    suspend fun applyDismiss(notif: NotificationListener.Recent): Boolean {
        val listener = NotificationListener.instance ?: return false
        return runCatching {
            listener.cancelNotification(notif.key)
            actionStore.bumpAutoDismissed(notif.packageName, notif.title, notif.text)
            Log.d(TAG, "auto-dismissed ${notif.packageName} key=${notif.key} title='${notif.title}'")
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "Mythara/NotifDec"
    }
}
