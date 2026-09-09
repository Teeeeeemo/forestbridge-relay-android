package com.forestbridge.relay

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Locale

data class WeChatCallState(
    val phase: String,
    val kind: String
)

class WeChatNotificationListenerService : NotificationListenerService() {

    companion object {
        const val ACTION_CALL_STATE_CHANGED =
            "com.forestbridge.relay.WECHAT_CALL_STATE_CHANGED"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_KIND = "kind"

        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val PREFS = "wechat_call_state"
        private const val KEY_PHASE = "phase"
        private const val KEY_KIND = "kind"
        private const val TAG = "ForestBridgeWeChat"

        fun readActiveCall(context: Context): WeChatCallState? {
            val preferences = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            val phase = preferences.getString(KEY_PHASE, null) ?: return null
            if (phase != "ringing" && phase != "active") return null
            return WeChatCallState(
                phase = phase,
                kind = preferences.getString(KEY_KIND, "unknown") ?: "unknown"
            )
        }
    }

    private data class CallSignal(
        val phase: String,
        val kind: String
    )

    private val activeCallKeys = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingEnd: Runnable? = null
    private var lastPhase = ""
    private var lastKind = "unknown"

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications
            ?.filter { it.packageName == WECHAT_PACKAGE }
            ?.forEach(::processNotification)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != WECHAT_PACKAGE) return
        processNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != WECHAT_PACKAGE) return
        if (!activeCallKeys.remove(sbn.key)) return
        if (activeCallKeys.isEmpty()) scheduleEnded(lastKind)
    }

    override fun onDestroy() {
        pendingEnd?.let(handler::removeCallbacks)
        pendingEnd = null
        super.onDestroy()
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val signal = classify(sbn.notification) ?: return

        if (signal.phase == "ended") {
            activeCallKeys.clear()
            scheduleEnded(signal.kind)
            return
        }

        cancelPendingEnd()
        activeCallKeys.add(sbn.key)
        emit(signal.phase, signal.kind)
    }

    private fun classify(notification: Notification): CallSignal? {
        val text = notificationText(notification)
            .lowercase(Locale.ROOT)

        val kind = when {
            text.contains("视频") || text.contains("video") -> "video"
            text.contains("语音") || text.contains("voice") ||
                text.contains("audio") -> "audio"
            else -> "unknown"
        }

        val ended = listOf(
            "通话结束",
            "已结束",
            "已取消",
            "已拒绝",
            "未接听",
            "其它设备接听",
            "其他设备接听",
            "call ended",
            "missed call",
            "declined",
            "cancelled",
            "canceled"
        ).any(text::contains)
        if (ended) return CallSignal("ended", kind)

        val callPhrase = listOf(
            "语音通话",
            "视频通话",
            "语音来电",
            "视频来电",
            "voice call",
            "video call",
            "audio call"
        ).any(text::contains)

        val explicitIncoming =
            (text.contains("邀请你") || text.contains("发来") ||
                text.contains("来电") || text.contains("calling you") ||
                text.contains("incoming")) && callPhrase

        val systemCallSignal =
            notification.category == Notification.CATEGORY_CALL ||
                (notification.fullScreenIntent != null && callPhrase)

        if (!explicitIncoming && !systemCallSignal) return null

        val active = listOf(
            "通话中",
            "正在通话",
            "通话时长",
            "in call",
            "ongoing call"
        ).any(text::contains)

        return CallSignal(
            phase = if (active) "active" else "ringing",
            kind = kind
        )
    }

    private fun notificationText(notification: Notification): String {
        val extras = notification.extras
        val parts = mutableListOf<CharSequence?>(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT)
        )
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.let(parts::addAll)
        return parts
            .filterNotNull()
            .joinToString(" ")
    }

    private fun scheduleEnded(kind: String) {
        cancelPendingEnd()
        val runnable = Runnable {
            pendingEnd = null
            if (activeCallKeys.isEmpty()) emit("ended", kind)
        }
        pendingEnd = runnable
        handler.postDelayed(runnable, 900)
    }

    private fun cancelPendingEnd() {
        pendingEnd?.let(handler::removeCallbacks)
        pendingEnd = null
    }

    private fun emit(phase: String, kind: String) {
        if (phase == lastPhase && kind == lastKind) return
        lastPhase = phase
        lastKind = kind

        val editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        if (phase == "ended") {
            editor.remove(KEY_PHASE).remove(KEY_KIND)
        } else {
            editor.putString(KEY_PHASE, phase).putString(KEY_KIND, kind)
        }
        editor.apply()

        sendBroadcast(
            Intent(ACTION_CALL_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_PHASE, phase)
                .putExtra(EXTRA_KIND, kind)
        )
        Log.i(TAG, "Detected WeChat call notification: $kind/$phase")
    }
}
