package com.wework.forwarder

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 主界面
 *
 * 配置群信息 → 检查权限 → 启动/停止采集服务
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var etSourceGroup: EditText
    private lateinit var etTargetGroups: EditText
    private lateinit var etLookback: EditText
    private lateinit var etPollInterval: EditText
    private lateinit var etEnterWait: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnDump: Button
    private lateinit var btnExport: Button
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化存储（必须最先调用）
        Storage.init(this)

        initViews()
        loadConfig()
        setupButtons()
        setupLogCallback()
        loadHistoryLogs()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun initViews() {
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        btnAccessibility = findViewById(R.id.btn_accessibility)
        etSourceGroup = findViewById(R.id.et_source_group)
        etTargetGroups = findViewById(R.id.et_target_groups)
        etLookback = findViewById(R.id.et_lookback)
        etPollInterval = findViewById(R.id.et_poll_interval)
        etEnterWait = findViewById(R.id.et_enter_wait)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnDump = findViewById(R.id.btn_dump)
        btnExport = findViewById(R.id.btn_export)
        tvLog = findViewById(R.id.tv_log)
        scrollLog = findViewById(R.id.scroll_log)
    }

    private fun loadConfig() {
        Storage.loadUserConfig(this)
        etSourceGroup.setText(Config.sourceGroup)
        etTargetGroups.setText(Config.targetGroups.joinToString(","))
        etLookback.setText(Config.lookbackMinutes.toString())
        etPollInterval.setText(Config.pollIntervalSeconds.toString())
        etEnterWait.setText(Config.enterGroupWaitSeconds.toString())
    }

    private fun setupButtons() {
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStart.setOnClickListener { startCollector() }
        btnStop.setOnClickListener { stopCollector() }
        btnDump.setOnClickListener { dumpUiTree() }
        btnExport.setOnClickListener { exportFiles() }
    }

    private fun setupLogCallback() {
        CollectorService.logCallback = { line ->
            runOnUiThread {
                val current = tvLog.text?.toString() ?: ""
                val lines = current.split("\n").toMutableList()
                if (lines.size > 200) {
                    // 保留最新150行
                    val trimmed = lines.takeLast(150)
                    lines.clear()
                    lines.addAll(trimmed)
                }
                lines.add(line)
                tvLog.text = lines.joinToString("\n")
                scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    /**
     * 检查无障碍服务是否已开启
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name == WeWorkAccessibilityService::class.java.name
        }
    }

    private fun updateAccessibilityStatus() {
        if (isAccessibilityServiceEnabled()) {
            tvAccessibilityStatus.text = "无障碍服务：已开启"
            tvAccessibilityStatus.setTextColor(0xFF43A047.toInt())
            btnAccessibility.text = "已开启"
        } else {
            tvAccessibilityStatus.text = "无障碍服务：未开启"
            tvAccessibilityStatus.setTextColor(0xFFF44336.toInt())
            btnAccessibility.text = "去开启"
        }
    }

    private fun startCollector() {
        if (CollectorService.isRunning) {
            Toast.makeText(this, "已经在运行中", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限，以便在企微界面显示状态", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // 读取 UI 上的配置
        Config.sourceGroup = etSourceGroup.text.toString().trim()
        Config.targetGroups = etTargetGroups.text.toString().trim()
            .split(Regex("[,，]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        Config.lookbackMinutes = etLookback.text.toString().toIntOrNull() ?: 10
        Config.pollIntervalSeconds = etPollInterval.text.toString().toIntOrNull() ?: 30
        Config.enterGroupWaitSeconds = etEnterWait.text.toString().toIntOrNull() ?: 3

        // 校验
        if (Config.sourceGroup.isEmpty()) {
            Toast.makeText(this, "请填写源群名称", Toast.LENGTH_SHORT).show()
            return
        }
        if (Config.targetGroups.isEmpty()) {
            Toast.makeText(this, "请填写至少一个目标群", Toast.LENGTH_SHORT).show()
            return
        }

        // 提示分批信息
        if (Config.targetGroups.size > Config.BATCH_SIZE) {
            val batches = (Config.targetGroups.size + Config.BATCH_SIZE - 1) / Config.BATCH_SIZE
            Toast.makeText(this,
                "${Config.targetGroups.size} 个目标群，将分 $batches 批转发",
                Toast.LENGTH_SHORT).show()
        }

        // 保存配置
        Storage.saveUserConfig()

        // 启动前台服务
        val intent = Intent(this, CollectorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "采集任务已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopCollector() {
        if (!CollectorService.isRunning) {
            Toast.makeText(this, "当前没有运行中的任务", Toast.LENGTH_SHORT).show()
            return
        }
        CollectorService.requestStop()
        stopService(Intent(this, CollectorService::class.java))
        Toast.makeText(this, "已发送停止信号", Toast.LENGTH_SHORT).show()
        appendLog("用户手动停止")
    }

    /**
     * 从日志文件加载历史日志（App 启动时恢复上次日志）
     */
    private fun loadHistoryLogs() {
        val recentLogs = Storage.readRecentLogs(150)
        if (recentLogs.isNotEmpty()) {
            tvLog.text = recentLogs.joinToString("\n")
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /**
     * dump 当前企业微信 UI 控件树（调试用）
     *
     * 优先 dump 企微窗口；如果找不到企微窗口，dump 当前活动窗口并标注
     */
    private fun dumpUiTree() {
        val service = WeWorkAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        // 遍历所有窗口，优先找企微窗口
        var root: android.view.accessibility.AccessibilityNodeInfo? = null
        var rootSource = "unknown"
        try {
            for (window in service.windows) {
                val r = window.root ?: continue
                if (r.packageName?.toString() == Config.WEWORK_PACKAGE) {
                    root = r
                    rootSource = "企微窗口"
                    break
                }
            }
        } catch (_: Exception) {}

        // 降级到 active window
        if (root == null) {
            root = service.rootInActiveWindow
            rootSource = "当前活动窗口(非企微)"
        }

        if (root == null) {
            appendLog("无法获取根节点，请确保企业微信在前台或最近使用过")
            return
        }

        val dump = NodeFinder.dumpTree(root)
        val header = buildString {
            appendLine("来源: $rootSource")
            appendLine("根节点包名: ${root.packageName}")
            appendLine("事件包名: ${service.currentPackage}")
            appendLine("活动: ${service.currentActivity}")
            appendLine("时间: ${Storage.now()}")
            appendLine("屏幕: ${resources.displayMetrics.widthPixels} x ${resources.displayMetrics.heightPixels}")
            appendLine("==============================")
        }

        // 保存到内部存储
        val filename = "ui_dump_${System.currentTimeMillis()}.txt"
        val path = Storage.saveDump(filename, header + dump)
        if (path != null) {
            appendLog("控件树已保存($rootSource): $filename")
            Toast.makeText(this, "控件树已保存($rootSource)", Toast.LENGTH_SHORT).show()
        } else {
            appendLog("保存控件树失败")
        }

        // 显示到日志区域
        appendLog("--- 控件树 ---")
        val lines = dump.split("\n")
        val preview = if (lines.size > 100) lines.take(100).joinToString("\n") + "\n... (共${lines.size}行)" else dump
        appendLog(preview)
    }

    /**
     * 导出所有数据文件：打包为 zip 并通过系统分享发出
     * 无需额外存储权限，可以直接发微信/保存到文件等
     */
    private fun exportFiles() {
        val srcDir = Storage.getDataDir()
        if (srcDir == null || !srcDir.exists()) {
            Toast.makeText(this, "没有数据可导出", Toast.LENGTH_SHORT).show()
            return
        }

        val files = srcDir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "没有数据文件", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // 打包为 zip（存到 cache 目录，FileProvider 可以访问）
            val zipFile = File(cacheDir, "wework-collector-export.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (file in files) {
                    zos.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            // 通过 FileProvider 生成 content:// URI
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)

            // 发起分享
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "企微转发日志导出")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "导出日志文件"))

            appendLog("已打包 ${files.size} 个文件，请选择分享方式")
        } catch (e: Exception) {
            appendLog("导出失败: ${e.message}")
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun appendLog(msg: String) {
        val line = "[${Storage.now()}] $msg"
        val current = tvLog.text?.toString() ?: ""
        val lines = current.split("\n").toMutableList()
        if (lines.size > 200) {
            val trimmed = lines.takeLast(150)
            lines.clear()
            lines.addAll(trimmed)
        }
        lines.add(line)
        tvLog.text = lines.joinToString("\n")
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        CollectorService.logCallback = null
    }
}
