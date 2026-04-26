package com.wework.forwarder

import android.util.DisplayMetrics
import kotlin.random.Random

/**
 * 手势工具
 *
 * 提供随机偏移、屏幕坐标计算等辅助方法
 */
object GestureHelper {

    /**
     * 生成随机坐标偏移（模拟真人点击位置不精确）
     */
    fun randomOffset(max: Int = Config.CLICK_OFFSET_MAX): Float {
        return Random.nextInt(-max, max + 1).toFloat()
    }

    /**
     * 生成随机额外延时
     */
    fun randomExtra(max: Int = Config.RANDOM_DELAY_MAX): Long {
        return Random.nextLong(0, max.toLong() + 1)
    }

    /**
     * 等待指定毫秒 + 随机偏移
     */
    fun delay(baseMs: Long = Config.CLICK_DELAY) {
        Thread.sleep(baseMs + randomExtra())
    }

    /**
     * 精确等待，不加随机偏移
     */
    fun delayExact(ms: Long) {
        Thread.sleep(ms)
    }

    /**
     * 向上滑动（翻看历史消息）
     */
    fun swipeUp(service: WeWorkAccessibilityService, metrics: DisplayMetrics) {
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        service.swipe(w / 2, h * 0.3f, w / 2, h * 0.7f)
        delay(Config.SWIPE_DELAY)
    }

    /**
     * 向下滑动（查看最新消息）
     */
    fun swipeDown(service: WeWorkAccessibilityService, metrics: DisplayMetrics) {
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        service.swipe(w / 2, h * 0.7f, w / 2, h * 0.3f)
        delay(Config.SWIPE_DELAY)
    }
}
