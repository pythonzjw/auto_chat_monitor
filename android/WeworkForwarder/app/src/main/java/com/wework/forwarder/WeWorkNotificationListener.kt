package com.wework.forwarder

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 企微通知监听服务
 *
 * 监听企微通知，当源群有新消息时通过 NotificationSignal 唤醒 CollectorService。
 * 只做触发器，不替代徽章扫描——精确未读数仍由 Navigator.findUnreadCountForGroup() 提供。
 */
class WeWorkNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"

        @Volatile
        var instance: WeWorkNotificationListener? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "通知监听服务已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.i(TAG, "通知监听服务已断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != Config.WEWORK_PACKAGE) return
        if (!CollectorService.isRunning) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        val sourceGroup = Config.sourceGroup
        if (sourceGroup.isEmpty()) return

        val isSourceGroup = title == sourceGroup
                || title.startsWith("$sourceGroup(")
                || title.startsWith("$sourceGroup（")

        if (isSourceGroup) {
            Log.i(TAG, "源群 [$sourceGroup] 有新消息: $text")
            Config.uiLog?.invoke("[通知] 源群新消息: ${text.take(30)}")
            NotificationSignal.emit(sourceGroup)
        }
    }
}
