package com.wework.forwarder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 悬浮日志窗口
 *
 * 默认只显示屏幕边缘小把手，点击后临时展开控制区，5 秒后自动收回。
 * 避免长期大面积悬浮遮挡企业微信。
 */
class FloatingLogView(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private lateinit var rootView: LinearLayout
    private lateinit var statusBar: TextView
    private lateinit var logArea: ScrollView
    private lateinit var logText: TextView
    private lateinit var btnRow: LinearLayout
    private lateinit var startBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var collapseBtn: TextView
    private lateinit var bottomStatus: TextView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bottomParams: WindowManager.LayoutParams

    private val autoCollapseRunnable = Runnable { collapseToHandle() }
    private var isExpanded = false
    private var isAttached = false
    private var visuallyHidden = true
    private val logLines = mutableListOf<String>()
    private val maxLines = 50

    // 状态：running / waiting / stopped / error
    private var currentStatus = "stopped"

    /** 回调 */
    var onStartClick: (() -> Unit)? = null
    var onStopClick: (() -> Unit)? = null
    var onDumpClick: (() -> Unit)? = null
    var onExportClick: (() -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    fun create() {
        if (isAttached) return

        // 根容器
        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC1B1B1B.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            alpha = 1f
        }

        // 状态条（收起时显示的小胶囊）
        statusBar = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            text = "≡"
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(4), dp(2), dp(4))
            visibility = View.VISIBLE
        }
        rootView.addView(statusBar)

        // 日志区域（展开时显示）
        logText = TextView(context).apply {
            setTextColor(0xFFCCCCCC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        logArea = ScrollView(context).apply {
            addView(logText)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(120)
            )
        }
        rootView.addView(logArea)

        // 按钮行（展开时显示）
        btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(2))
        }

        // 开始按钮
        startBtn = makeButton("开始", 0xFF2E7D32.toInt()) {
            onStartClick?.invoke()
        }
        btnRow.addView(startBtn)
        addSpacer(btnRow, dp(6))

        // 暂停按钮：等同停止采集
        stopBtn = makeButton("暂停", 0xFFC62828.toInt()) {
            onStopClick?.invoke()
            collapseToHandle()
        }
        btnRow.addView(stopBtn)
        addSpacer(btnRow, dp(6))

        // 收回按钮
        collapseBtn = makeButton("收回", 0xFF455A64.toInt()) {
            collapseToHandle()
        }
        btnRow.addView(collapseBtn)

        rootView.addView(btnRow)

        // 底部常驻状态条：不可触摸，避免影响企微点击/手势。
        bottomStatus = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setBackgroundColor(0xDD1B1B1B.toInt())
            setPadding(dp(20), dp(10), dp(20), dp(10))
            text = "监控采集群消息中..."
            visibility = View.GONE
        }

        // 点击状态条切换展开/收起
        statusBar.setOnClickListener { toggleExpand() }

        // 拖动支持
        setupDrag()

        // WindowManager 参数
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            dp(12),
            dp(48),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
            width = dp(12)
            height = dp(48)
        }

        bottomParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(84)
        }

        wm.addView(rootView, params)
        wm.addView(bottomStatus, bottomParams)
        isAttached = true
        collapseToHandle()
        updateButtonState()
        updateBottomStatus()
    }

    fun destroy() {
        if (!isAttached) return
        try {
            wm.removeView(rootView)
        } catch (_: Exception) {
        }
        try {
            wm.removeView(bottomStatus)
        } catch (_: Exception) {
        }
        isAttached = false
    }

    /**
     * 追加一行日志
     */
    fun appendLog(line: String) {
        handler.post {
            logLines.add(line)
            if (logLines.size > maxLines) {
                logLines.removeAt(0)
            }

            // 更新状态条
            val statusIcon = statusIcon()
            statusBar.text = if (isExpanded) "$statusIcon ${line.take(40)}" else statusIcon

            // 更新日志区
            val recentLines = logLines.takeLast(20)
            logText.text = recentLines.joinToString("\n")
            logArea.post { logArea.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /**
     * 设置状态并更新按钮可用性
     */
    fun setStatus(status: String) {
        currentStatus = status
        handler.post {
            updateButtonState()
            updateBottomStatus()
            if (!isExpanded && ::statusBar.isInitialized) {
                statusBar.text = statusIcon()
            }
        }
    }

    /**
     * 控制悬浮窗是否接收触摸事件
     *
     * 任务运行时设为 false：加 FLAG_NOT_TOUCHABLE，让 dispatchGesture 的触摸事件
     * 穿透悬浮窗到达下层企微控件（避免悬浮窗挡住对勾、群勾选等按钮）。
     * 任务停止后设为 true：恢复可触摸，用户可以点击按钮。
     */
    fun setTouchable(touchable: Boolean) {
        if (!isAttached) return
        handler.post {
            if (visuallyHidden) {
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else if (touchable) {
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try {
                wm.updateViewLayout(rootView, params)
            } catch (_: Exception) {}
        }
    }

    /**
     * hidden=true 时收为边缘小把手；hidden=false 时临时展开控制区。
     */
    fun setVisualHidden(hidden: Boolean) {
        if (!isAttached) return
        handler.post {
            if (hidden) collapseToHandle() else expandPanel()
        }
    }

    private fun statusIcon(): String {
        return when (currentStatus) {
            "running" -> "🟢"
            "error" -> "🔴"
            "waiting" -> "🟡"
            else -> "⚪"
        }
    }

    private fun bottomStatusText(): String? {
        return when (currentStatus) {
            "running", "waiting" -> "监控采集群消息中..."
            "error" -> "转发异常，正在恢复..."
            else -> null
        }
    }

    private fun updateBottomStatus() {
        if (!isAttached || !::bottomStatus.isInitialized) return
        val text = bottomStatusText()
        if (text == null) {
            bottomStatus.visibility = View.GONE
        } else {
            bottomStatus.text = text
            bottomStatus.visibility = View.VISIBLE
        }
        try { wm.updateViewLayout(bottomStatus, bottomParams) } catch (_: Exception) {}
    }

    private fun updateButtonState() {
        val isActive = currentStatus == "running" || currentStatus == "waiting"
        startBtn.alpha = if (isActive) 0.4f else 1.0f
        startBtn.isClickable = !isActive
        stopBtn.alpha = if (isActive) 1.0f else 0.4f
        stopBtn.isClickable = isActive
    }

    private fun toggleExpand() {
        if (isExpanded) collapseToHandle() else expandPanel()
    }

    private fun expandPanel() {
        if (!isAttached) return
        handler.removeCallbacks(autoCollapseRunnable)
        visuallyHidden = false
        isExpanded = true
        rootView.alpha = 1f
        rootView.setBackgroundColor(0xDD1B1B1B.toInt())
        statusBar.visibility = View.VISIBLE
        logArea.visibility = View.GONE
        btnRow.visibility = View.VISIBLE
        val statusIcon = statusIcon()
        val label = when (currentStatus) {
            "running" -> "运行中"
            "waiting" -> "等待中"
            "error" -> "异常"
            else -> "已暂停"
        }
        statusBar.text = "$statusIcon $label"
        params.width = dp(210)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        try { wm.updateViewLayout(rootView, params) } catch (_: Exception) {}
        handler.postDelayed(autoCollapseRunnable, 5000)
    }

    private fun collapseToHandle() {
        if (!isAttached) return
        handler.removeCallbacks(autoCollapseRunnable)
        visuallyHidden = true
        isExpanded = false
        rootView.alpha = 1f
        rootView.setBackgroundColor(0xAA1B1B1B.toInt())
        statusBar.visibility = View.VISIBLE
        logArea.visibility = View.GONE
        btnRow.visibility = View.GONE
        statusBar.text = statusIcon()
        params.width = dp(12)
        params.height = dp(48)
        params.x = 0
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        try { wm.updateViewLayout(rootView, params) } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        var startX = 0f
        var startY = 0f
        var startParamX = 0
        var startParamY = 0
        var isDragging = false

        rootView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startParamX = params.x
                    startParamY = params.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = startParamX - dx.toInt()  // END gravity 方向相反
                        params.y = startParamY + dy.toInt()
                        if (isAttached) wm.updateViewLayout(rootView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleExpand()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun makeButton(text: String, bgColor: Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackgroundColor(bgColor)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { onClick() }
        }
    }

    private fun addSpacer(parent: LinearLayout, width: Int) {
        parent.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(width, 1)
        })
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
