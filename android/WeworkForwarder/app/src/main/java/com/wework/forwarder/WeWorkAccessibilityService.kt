package com.wework.forwarder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务
 *
 * 核心能力：
 * - 读取企业微信控件树
 * - 执行手势操作（点击、长按、滑动）
 * - 监听窗口变化
 */
class WeWorkAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WeWorkA11y"

        // 单例引用，供其他模块调用
        @Volatile
        var instance: WeWorkAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    // 当前前台应用包名
    @Volatile
    var currentPackage: String = ""
        private set

    // 当前 Activity 类名
    @Volatile
    var currentActivity: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // 记录当前前台应用
        event.packageName?.let { currentPackage = it.toString() }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.let { currentActivity = it.toString() }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "无障碍服务已销毁")
    }

    // ===== 控件树操作 =====

    /**
     * 获取当前窗口根节点
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "获取根节点失败: ${e.message}")
            null
        }
    }

    // ===== 手势操作 =====

    /**
     * 点击坐标（带随机偏移）
     */
    fun clickAt(x: Float, y: Float): Boolean {
        val offsetX = GestureHelper.randomOffset()
        val offsetY = GestureHelper.randomOffset()
        val path = Path().apply { moveTo(x + offsetX, y + offsetY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGestureSync(gesture)
    }

    /**
     * 长按坐标（带随机偏移）
     */
    fun longPressAt(x: Float, y: Float, duration: Long = Config.LONG_PRESS_DURATION): Boolean {
        val offsetX = GestureHelper.randomOffset()
        val offsetY = GestureHelper.randomOffset()
        val path = Path().apply { moveTo(x + offsetX, y + offsetY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration + GestureHelper.randomExtra(200)))
            .build()
        return dispatchGestureSync(gesture)
    }

    /**
     * 滑动手势
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1 + GestureHelper.randomOffset(30), y1 + GestureHelper.randomOffset(20))
            lineTo(x2 + GestureHelper.randomOffset(30), y2 + GestureHelper.randomOffset(20))
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration + GestureHelper.randomExtra(200)))
            .build()
        return dispatchGestureSync(gesture)
    }

    /**
     * 同步执行手势，等待完成
     */
    private fun dispatchGestureSync(gesture: GestureDescription, timeoutMs: Long = 3000): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }, null)
        if (!result) return false
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return success
    }

    // ===== 全局操作 =====

    /**
     * 按返回键
     */
    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * 当前是否在企业微信中
     */
    fun isInWeWork(): Boolean {
        return currentPackage == Config.WEWORK_PACKAGE
    }
}
