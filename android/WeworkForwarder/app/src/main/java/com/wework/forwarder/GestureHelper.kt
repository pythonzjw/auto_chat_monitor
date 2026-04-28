package com.wework.forwarder

import android.util.DisplayMetrics
import kotlin.random.Random

/**
 * 手势工具
 *
 * 提供随机偏移、屏幕坐标计算等辅助方法
 * 所有延时方法都检查 isRunning，停止后立即返回
 */
object GestureHelper {

    fun randomOffset(max: Int = Config.CLICK_OFFSET_MAX): Float {
        return Random.nextInt(-max, max + 1).toFloat()
    }

    fun randomExtra(max: Int = Config.RANDOM_DELAY_MAX): Long {
        return Random.nextLong(0, max.toLong() + 1)
    }

    /**
     * 等待指定毫秒 + 随机偏移（分段 sleep，每段检查 isRunning）
     */
    fun delay(baseMs: Long = Config.CLICK_DELAY) {
        val total = baseMs + randomExtra()
        sleepInterruptible(total)
    }

    /**
     * 精确等待（分段 sleep，每段检查 isRunning）
     */
    fun delayExact(ms: Long) {
        sleepInterruptible(ms)
    }

    /**
     * 分段 sleep，每 200ms 检查一次停止标志
     */
    private fun sleepInterruptible(ms: Long) {
        val step = 200L
        var remaining = ms
        while (remaining > 0 && CollectorService.isRunning) {
            Thread.sleep(minOf(remaining, step))
            remaining -= step
        }
    }

    /**
     * 向上滑动（翻看历史消息）
     */
    fun swipeUp(service: WeWorkAccessibilityService, metrics: DisplayMetrics) {
        if (!CollectorService.isRunning) return
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        service.swipe(w / 2, h * 0.425f, w / 2, h * 0.575f)
        delay(Config.SWIPE_DELAY)
    }

    fun swipeDown(service: WeWorkAccessibilityService, metrics: DisplayMetrics) {
        if (!CollectorService.isRunning) return
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        service.swipe(w / 2, h * 0.575f, w / 2, h * 0.425f)
        delay(Config.SWIPE_DELAY)
    }
}
