package com.wework.forwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*

/**
 * 采集前台服务
 *
 * 启动后只显示悬浮窗，等用户点"开始"才运行采集循环。
 * 悬浮窗按钮：开始 / 停止 / 分析控件 / 导出
 */
class CollectorService : Service() {

    companion object {
        private const val TAG = "CollectorSvc"
        private const val CHANNEL_ID = "wework_forwarder_channel"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set

        /** 请求停止采集（不停止服务，只停止采集循环） */
        fun requestStop() {
            isRunning = false
        }

        // 日志回调，用于 Activity UI 显示
        var logCallback: ((String) -> Unit)? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var metrics: DisplayMetrics
    private var floatingLog: FloatingLogView? = null
    private var collectorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        metrics = resources.displayMetrics

        // 初始化存储（必须在 uiLog 之前）
        Storage.init(this)

        // 创建悬浮窗（需要权限）
        if (Settings.canDrawOverlays(this)) {
            floatingLog = FloatingLogView(this)
            floatingLog?.create()
            setupFloatingCallbacks()
        } else {
            Log.w(TAG, "没有悬浮窗权限，跳过创建悬浮窗")
        }

        // 设置全局 uiLog 回调
        Config.uiLog = { msg ->
            val line = "[${Storage.now()}] $msg"
            Log.i(TAG, msg)
            logCallback?.invoke(line)
            floatingLog?.appendLog(msg)
            Storage.appendLogLine(line)
        }
    }

    /**
     * 设置悬浮窗各按钮的回调
     */
    private fun setupFloatingCallbacks() {
        // 开始采集
        floatingLog?.onStartClick = {
            if (collectorJob?.isActive == true) {
                log("采集已在运行中")
            } else {
                isRunning = true
                floatingLog?.setStatus("running")
                // 任务运行时悬浮窗不接收触摸，避免挡住 dispatchGesture 的坐标点击
                floatingLog?.setTouchable(false)
                log("用户点击开始采集")
                collectorJob = serviceScope.launch {
                    try {
                        runCollector()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("采集异常: ${e.message}")
                        floatingLog?.setStatus("error")
                    } finally {
                        isRunning = false
                        floatingLog?.setStatus("stopped")
                        // 任务结束后恢复悬浮窗可触摸
                        floatingLog?.setTouchable(true)
                        log("采集已停止")
                    }
                }
            }
        }

        // 停止采集
        floatingLog?.onStopClick = {
            if (isRunning) {
                isRunning = false
                log("用户点击停止采集")
                floatingLog?.setStatus("stopped")
                // 恢复悬浮窗可触摸
                floatingLog?.setTouchable(true)
            } else {
                log("当前未在运行")
            }
        }

        // 分析控件
        floatingLog?.onDumpClick = {
            val service = WeWorkAccessibilityService.instance
            if (service != null) {
                try {
                    val roots = service.getAllRootNodes()
                    val sb = StringBuilder()
                    sb.appendLine("=== 手动 dump ===")
                    sb.appendLine("Activity: ${service.currentActivity}")
                    sb.appendLine("包名: ${service.currentPackage}")
                    sb.appendLine("时间: ${Storage.now()}")
                    sb.appendLine("屏幕: ${metrics.widthPixels}x${metrics.heightPixels}")
                    sb.appendLine("窗口数: ${roots.size}")
                    sb.appendLine("==============================")
                    for ((i, root) in roots.withIndex()) {
                        val rect = Rect()
                        root.getBoundsInScreen(rect)
                        sb.appendLine("--- 窗口 ${i + 1} (${rect.width()}x${rect.height()}) ---")
                        sb.append(NodeFinder.dumpTree(root))
                    }
                    val filename = "dump_手动_${System.currentTimeMillis()}.txt"
                    Storage.saveDump(filename, sb.toString())
                    log("已保存: $filename")
                } catch (e: Exception) {
                    log("dump 异常: ${e.message}")
                }
            } else {
                log("无障碍服务未启动，无法 dump")
            }
        }

        // 导出日志
        floatingLog?.onExportClick = {
            exportFiles()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // 不自动启动采集，等用户在悬浮窗点"开始"
        floatingLog?.setStatus("stopped")
        log("服务已就绪，点击悬浮窗「开始」按钮启动采集")

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()

        floatingLog?.destroy()
        floatingLog = null
        Config.uiLog = null

        Log.i(TAG, "采集服务已停止")
    }

    /**
     * v2.0 主循环：群外监控未读徽章 → 命中后进群转最后 K 条 → 退出回列表
     *
     * 与 v1.x 的根本区别:
     *   - 不再保存书签(三重锁/指纹/分隔线全部不需要)
     *   - 待机时停在"消息列表"页(不在源群里),用户操作互不干扰
     *   - "K 条新消息"由企微红色徽章告诉我们,不再启发式判断
     */
    private suspend fun runCollector() {
        val service = WeWorkAccessibilityService.instance
        if (service == null) {
            log("无障碍服务未启动！")
            floatingLog?.setStatus("error")
            return
        }

        log("========================================")
        log("企业微信消息转发 v2.0 - 启动")
        log("源群: ${Config.sourceGroup}")
        log("目标群 (${Config.targetGroups.size}个): ${Config.targetGroups.joinToString(", ")}")
        log("轮询间隔: ${Config.pollIntervalSeconds} 秒")
        log("单次最大转发: ${Config.maxForwardCount} 条")
        log("========================================")

        if (!Navigator.ensureWeWorkForeground(service)) {
            log("无法启动企业微信，退出")
            floatingLog?.setStatus("error")
            return
        }

        // Phase 1: 进入消息列表待机(不进任何群)
        log("===== Phase 1: 进入消息列表待机 =====")
        if (!Navigator.goToMessageList(service)) {
            log("无法到达消息列表，退出")
            floatingLog?.setStatus("error")
            return
        }
        delay(1000)

        // Phase 2: 监控源群未读徽章
        log("===== Phase 2: 监控源群未读徽章 =====")
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 10

        while (isRunning && coroutineContext.isActive) {
            try {
                floatingLog?.setStatus("waiting")
                log("等待 ${Config.pollIntervalSeconds} 秒后扫描徽章...")
                for (w in 0 until Config.pollIntervalSeconds) {
                    if (!isRunning || !coroutineContext.isActive) return
                    delay(1000)
                }
                if (!isRunning || !coroutineContext.isActive) return

                floatingLog?.setStatus("running")

                // 确保企微在前台 + 在消息列表(用户可能切走了)
                if (!service.isInWeWork()) {
                    log("企微不在前台,尝试恢复...")
                    Navigator.ensureWeWorkForeground(service)
                    delay(2000)
                }
                Navigator.goToMessageList(service)
                delay(500)

                val unread = Navigator.findUnreadCountForGroup(service, metrics, Config.sourceGroup)
                when {
                    unread == null -> log("[扫描] 找不到源群 ${Config.sourceGroup}(列表上不存在?)")
                    unread == 0 -> log("[扫描] 源群无未读")
                    else -> {
                        log("[扫描] 源群有 $unread 条未读,进入转发...")
                        if (!Navigator.enterGroup(service, metrics, Config.sourceGroup)) {
                            log("✗ 进入源群失败")
                            consecutiveErrors++
                            continue
                        }
                        delay(Config.enterGroupWaitSeconds * 1000L)

                        val ok = MessageForwarder.forwardNewMessages(service, metrics, unread)
                        log(if (ok) "✓ 转发完成" else "✗ 转发失败")
                        if (!ok) floatingLog?.setStatus("error")

                        // 退出回消息列表(供下一轮扫描)
                        Navigator.exitGroup(service)
                        delay(1000)
                    }
                }
                consecutiveErrors = 0

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveErrors++
                floatingLog?.setStatus("error")
                log("轮询异常 ($consecutiveErrors/$maxConsecutiveErrors): ${e.message}")
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    log("连续错误次数过多，停止运行")
                    return
                }
                delay(5000)
                try {
                    Navigator.goToMessageList(service)
                    delay(1000)
                } catch (e2: Exception) {
                    log("恢复失败: ${e2.message}")
                }
            }
        }

        log("========================================")
        log("采集任务已停止")
        log("========================================")
    }

    /**
     * 导出所有数据文件为 zip 并分享
     */
    private fun exportFiles() {
        val srcDir = Storage.getDataDir()
        if (srcDir == null || !srcDir.exists()) {
            log("没有数据可导出")
            return
        }

        val files = srcDir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) {
            log("没有数据文件")
            return
        }

        try {
            val zipFile = File(cacheDir, "wework-collector-export.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (file in files) {
                    zos.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "企微转发日志导出")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Service 中启动 Activity 需要 FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(shareIntent, "导出日志文件").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })

            log("已打包 ${files.size} 个文件，请选择分享方式")
        } catch (e: Exception) {
            log("导出失败: ${e.message}")
        }
    }

    private fun log(msg: String) {
        val line = "[${Storage.now()}] $msg"
        Log.i(TAG, msg)
        logCallback?.invoke(line)
        floatingLog?.appendLog(msg)
        Storage.appendLogLine(line)
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
                .setContentText("服务就绪")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("企微消息转发")
                .setContentText("服务就绪")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .build()
        }
    }
}
