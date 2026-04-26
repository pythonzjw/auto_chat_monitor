package com.wework.forwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import kotlinx.coroutines.*

/**
 * 采集前台服务
 *
 * 主循环：轮询 → 检查新消息 → 转发 → 回到源群
 */
class CollectorService : Service() {

    companion object {
        private const val TAG = "CollectorSvc"
        private const val CHANNEL_ID = "wework_forwarder_channel"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set

        /** 请求停止采集 */
        fun requestStop() {
            isRunning = false
        }

        // 日志回调，用于 UI 显示
        var logCallback: ((String) -> Unit)? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var metrics: DisplayMetrics

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        metrics = resources.displayMetrics
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        isRunning = true

        serviceScope.launch {
            try {
                runCollector()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("主线程异常: ${e.message}")
            } finally {
                isRunning = false
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        Log.i(TAG, "采集服务已停止")
    }

    /**
     * 主采集循环
     */
    private suspend fun runCollector() {
        val service = WeWorkAccessibilityService.instance
        if (service == null) {
            log("无障碍服务未启动！")
            return
        }

        log("========================================")
        log("企业微信消息采集转发 - 启动")
        log("源群: ${Config.sourceGroup}")
        log("目标群 (${Config.targetGroups.size}个): ${Config.targetGroups.joinToString(", ")}")
        log("回溯: ${Config.lookbackMinutes} 分钟")
        log("轮询间隔: ${Config.pollIntervalSeconds} 秒")
        log("========================================")

        // 初始化存储
        Storage.init()

        // 确保企业微信在前台
        if (!Navigator.ensureWeWorkForeground(service)) {
            log("无法启动企业微信，退出")
            return
        }

        // Phase 1: 进入源群并执行首次转发
        log("===== Phase 1: 进入源群 =====")
        if (!Navigator.enterGroup(service, metrics, Config.sourceGroup)) {
            log("无法进入源群: ${Config.sourceGroup}，退出")
            return
        }
        log("已进入源群，等待 ${Config.enterGroupWaitSeconds} 秒...")
        delay(Config.enterGroupWaitSeconds * 1000L)

        // 检查是否有新消息需要转发
        if (MessageCollector.hasNewMessages(service)) {
            log("发现新消息，执行首次转发...")
            val success = MessageForwarder.forwardNewMessages(service, metrics)
            log(if (success) "首次转发完成" else "首次转发失败，继续进入监控模式")
            Navigator.enterGroup(service, metrics, Config.sourceGroup)
            delay(Config.enterGroupWaitSeconds * 1000L)
        } else {
            log("没有新消息，直接进入监控模式")
            val lastMsg = MessageCollector.getLastMessage(service)
            if (lastMsg != null && Storage.getBookmark() == null) {
                Storage.saveBookmark(lastMsg.sender, lastMsg.content, lastMsg.time)
                log("已记录初始书签")
            }
        }

        // Phase 2: 持续监控
        log("===== Phase 2: 持续监控新消息 =====")
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 10

        while (isRunning && isActive) {
            try {
                // 等待轮询间隔
                log("等待 ${Config.pollIntervalSeconds} 秒后检查...")
                for (w in 0 until Config.pollIntervalSeconds) {
                    if (!isRunning || !isActive) return
                    delay(1000)
                }
                if (!isRunning || !isActive) return

                // 确保企业微信在前台
                if (!service.isInWeWork()) {
                    log("企业微信不在前台，尝试恢复...")
                    Navigator.ensureWeWorkForeground(service)
                    delay(2000)
                    Navigator.enterGroup(service, metrics, Config.sourceGroup)
                    delay(Config.enterGroupWaitSeconds * 1000L)
                }

                // 检查新消息
                if (MessageCollector.hasNewMessages(service)) {
                    log("发现新消息，开始转发...")
                    val success = MessageForwarder.forwardNewMessages(service, metrics)
                    log(if (success) "转发完成" else "转发失败")
                    Navigator.enterGroup(service, metrics, Config.sourceGroup)
                    delay(Config.enterGroupWaitSeconds * 1000L)
                } else {
                    if (Config.debug) log("暂无新消息")
                }

                consecutiveErrors = 0

                // 定期清理过期指纹
                if (Math.random() < 0.01) Storage.cleanOldFingerprints()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveErrors++
                log("轮询异常 ($consecutiveErrors/$maxConsecutiveErrors): ${e.message}")
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    log("连续错误次数过多，停止运行")
                    return
                }
                // 异常恢复
                delay(5000)
                try {
                    Navigator.goToMessageList(service)
                    delay(1000)
                    Navigator.enterGroup(service, metrics, Config.sourceGroup)
                    delay(Config.enterGroupWaitSeconds * 1000L)
                } catch (e2: Exception) {
                    log("恢复失败: ${e2.message}")
                }
            }
        }

        log("========================================")
        log("采集任务已停止")
        log("========================================")
    }

    private fun log(msg: String) {
        val line = "[${Storage.now()}] $msg"
        Log.i(TAG, msg)
        logCallback?.invoke(line)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "消息采集服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "企微消息采集转发后台服务" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("企微消息转发")
                .setContentText("采集运行中")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("企微消息转发")
                .setContentText("采集运行中")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .build()
        }
    }
}
