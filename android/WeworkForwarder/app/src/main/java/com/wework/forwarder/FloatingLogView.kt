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
 * 顶部半透明状态条：
 *   - 收起时：一行状态文字（绿/黄/红色圆点 + 最新日志）
 *   - 展开时：显示最近 8 行日志
 *   - 可拖动
 */
class FloatingLogView(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private lateinit var rootView: LinearLayout
    private lateinit var statusBar: TextView
    private lateinit var logArea: ScrollView
    private lateinit var logText: TextView
    private lateinit var params: WindowManager.LayoutParams

    private var isExpanded = false
    private var isAttached = false
    private val logLines = mutableListOf<String>()
    private val maxLines = 50

    // 状态：running / waiting / error
    private var currentStatus = "waiting"

    /** 点击"分析控件"按钮的回调 */
    var onDumpClick: (() -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    fun create() {
        if (isAttached) return

        // 根容器
        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD1B1B1B.toInt()) // 深色半透明
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        // 状态条（收起时显示）
        statusBar = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            text = "\u25CF 等待中"
            setPadding(dp(4), dp(2), dp(4), dp(2))
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
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
            tag = "btnRow"
        }
        val dumpBtn = TextView(context).apply {
            text = "分析控件"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(0xFF336699.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { onDumpClick?.invoke() }
        }
        btnRow.addView(dumpBtn)
        rootView.addView(btnRow)

        // 点击切换展开/收起
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
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        wm.addView(rootView, params)
        isAttached = true
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

            // 更新状态条（显示最新一行）
            val statusIcon = when (currentStatus) {
                "running" -> "\uD83D\uDFE2" // 绿色圆点
                "error" -> "\uD83D\uDD34"    // 红色圆点
                else -> "\uD83D\uDFE1"       // 黄色圆点
            }
            statusBar.text = "$statusIcon ${line.take(60)}"

            // 更新日志区
            val recentLines = logLines.takeLast(20)
            logText.text = recentLines.joinToString("\n")
            logArea.post { logArea.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /**
     * 设置状态
     */
    fun setStatus(status: String) {
        currentStatus = status
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        val vis = if (isExpanded) View.VISIBLE else View.GONE
        logArea.visibility = vis
        // 按钮行跟随展开/收起
        rootView.findViewWithTag<View>("btnRow")?.visibility = vis
        // 更新布局
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
                        params.x = startParamX + dx.toInt()
                        params.y = startParamY + dy.toInt()
                        if (isAttached) wm.updateViewLayout(rootView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 非拖动，触发点击
                        toggleExpand()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
