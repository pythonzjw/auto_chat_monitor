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
 * 收起时：右上角小胶囊（不挡下面的点击）
 * 展开时：日志区 + 按钮行（开始/停止/分析控件/导出）
 * 可拖动
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
    private lateinit var params: WindowManager.LayoutParams

    private var isExpanded = false
    private var isAttached = false
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
            setBackgroundColor(0xDD1B1B1B.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        // 状态条（收起时显示的小胶囊）
        statusBar = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            text = "\u25CF 待命"
            setPadding(dp(8), dp(4), dp(8), dp(4))
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

        // 停止按钮
        stopBtn = makeButton("停止", 0xFFC62828.toInt()) {
            onStopClick?.invoke()
        }
        btnRow.addView(stopBtn)
        addSpacer(btnRow, dp(6))

        // 分析控件按钮
        val dumpBtn = makeButton("分析控件", 0xFF336699.toInt()) {
            onDumpClick?.invoke()
        }
        btnRow.addView(dumpBtn)
        addSpacer(btnRow, dp(6))

        // 导出按钮
        val exportBtn = makeButton("导出", 0xFF6A1B9A.toInt()) {
            onExportClick?.invoke()
        }
        btnRow.addView(exportBtn)

        rootView.addView(btnRow)

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
            WindowManager.LayoutParams.WRAP_CONTENT,  // 不占满屏幕宽度
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START  // 左上角，避免遮挡选群页面右上角的对勾按钮
            x = 0
            y = 0
        }

        wm.addView(rootView, params)
        isAttached = true
        updateButtonState()
    }

    fun destroy() {
        if (!isAttached) return
        try {
            wm.removeView(rootView)
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
            val statusIcon = when (currentStatus) {
                "running" -> "\uD83D\uDFE2"
                "error" -> "\uD83D\uDD34"
                "waiting" -> "\uD83D\uDFE1"
                else -> "\u26AA"  // 灰色圆点 = stopped
            }
            statusBar.text = "$statusIcon ${line.take(40)}"

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
        handler.post { updateButtonState() }
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
            if (touchable) {
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

    private fun updateButtonState() {
        val isActive = currentStatus == "running" || currentStatus == "waiting"
        startBtn.alpha = if (isActive) 0.4f else 1.0f
        startBtn.isClickable = !isActive
        stopBtn.alpha = if (isActive) 1.0f else 0.4f
        stopBtn.isClickable = isActive
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        val vis = if (isExpanded) View.VISIBLE else View.GONE
        logArea.visibility = vis
        btnRow.visibility = vis

        // 展开时宽度撑满，收起时自适应
        params.width = if (isExpanded)
            WindowManager.LayoutParams.MATCH_PARENT
        else
            WindowManager.LayoutParams.WRAP_CONTENT

        if (isAttached) wm.updateViewLayout(rootView, params)
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
