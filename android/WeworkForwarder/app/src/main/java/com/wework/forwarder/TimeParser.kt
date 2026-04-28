package com.wework.forwarder

import java.util.Calendar
import java.util.Date

/**
 * 企业微信消息时间文本解析
 */
object TimeParser {

    // 星期文本到 Calendar.DAY_OF_WEEK 的映射（周日=1，周一=2，...，周六=7）
    private val DOW_MAP = mapOf(
        "一" to Calendar.MONDAY,
        "二" to Calendar.TUESDAY,
        "三" to Calendar.WEDNESDAY,
        "四" to Calendar.THURSDAY,
        "五" to Calendar.FRIDAY,
        "六" to Calendar.SATURDAY,
        "日" to Calendar.SUNDAY,
        "天" to Calendar.SUNDAY,
    )

    /**
     * 解析企业微信消息中的时间文本
     * 返回 Date 对象，解析失败返回 null
     */
    fun parseMessageTime(timeText: String?): Date? {
        if (timeText.isNullOrBlank()) return null
        val text = timeText.trim()

        val now = Calendar.getInstance()
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 格式: "HH:mm" — 跨日修复：若结果晚于现在，回退一天
        Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(text)?.let { m ->
            val cal = today.clone() as Calendar
            cal.set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
            cal.set(Calendar.MINUTE, m.groupValues[2].toInt())
            if (cal.after(now)) cal.add(Calendar.DAY_OF_MONTH, -1)
            return cal.time
        }

        // 格式: "昨天 HH:mm"
        Regex("^昨天\\s*(\\d{1,2}):(\\d{2})$").matchEntire(text)?.let { m ->
            val cal = today.clone() as Calendar
            cal.add(Calendar.DAY_OF_MONTH, -1)
            cal.set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
            cal.set(Calendar.MINUTE, m.groupValues[2].toInt())
            return cal.time
        }

        // 格式: "星期X HH:mm" — 实际计算偏移天数（一周内）
        Regex("^星期([一二三四五六日天])\\s*(\\d{1,2}):(\\d{2})$").matchEntire(text)?.let { m ->
            val targetDow = DOW_MAP[m.groupValues[1]] ?: return null
            val todayDow = today.get(Calendar.DAY_OF_WEEK)
            // diff = 今天到目标星期的回退天数；同一星期视为一周前
            var diff = (todayDow - targetDow + 7) % 7
            if (diff == 0) diff = 7
            val cal = today.clone() as Calendar
            cal.add(Calendar.DAY_OF_MONTH, -diff)
            cal.set(Calendar.HOUR_OF_DAY, m.groupValues[2].toInt())
            cal.set(Calendar.MINUTE, m.groupValues[3].toInt())
            return cal.time
        }

        // 格式: "M月D日 HH:mm" — 跨年修复：若结果晚于现在，年份-1
        Regex("^(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})$").matchEntire(text)?.let { m ->
            val cal = today.clone() as Calendar
            cal.set(Calendar.MONTH, m.groupValues[1].toInt() - 1)
            cal.set(Calendar.DAY_OF_MONTH, m.groupValues[2].toInt())
            cal.set(Calendar.HOUR_OF_DAY, m.groupValues[3].toInt())
            cal.set(Calendar.MINUTE, m.groupValues[4].toInt())
            if (cal.after(now)) cal.add(Calendar.YEAR, -1)
            return cal.time
        }

        // 格式: "yyyy/MM/dd HH:mm" 或 "yyyy-MM-dd HH:mm"
        Regex("^(\\d{4})[/\\-](\\d{1,2})[/\\-](\\d{1,2})\\s*(\\d{1,2}):(\\d{2})$").matchEntire(text)?.let { m ->
            val cal = Calendar.getInstance()
            cal.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(),
                m.groupValues[4].toInt(), m.groupValues[5].toInt(), 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.time
        }

        return null
    }

    /**
     * 判断时间是否在回溯范围内
     */
    fun isWithinLookback(msgTime: Date, lookbackMinutes: Int): Boolean {
        // 用 Long 字面量避免 Int 中间结果溢出（>35791 分钟时）
        val cutoff = Date(System.currentTimeMillis() - lookbackMinutes * 60_000L)
        return msgTime.time >= cutoff.time
    }
}
