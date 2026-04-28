package com.wework.forwarder

/**
 * 全局配置
 *
 * 可变字段（var）在主线程修改，采集循环（协程）中读取。
 * 已对高频读写字段加 @Volatile 保证可见性。
 */
object Config {
    // ===== 群配置 =====
    @Volatile var sourceGroup: String = ""
    @Volatile var targetGroups: List<String> = emptyList()

    // ===== 时间配置 =====
    @Volatile var lookbackMinutes: Int = 10
    @Volatile var enterGroupWaitSeconds: Int = 3
    @Volatile var pollIntervalSeconds: Int = 30

    // ===== 操作延时（毫秒） =====
    const val CLICK_DELAY = 800L
    const val SWIPE_DELAY = 1000L
    const val LONG_PRESS_DURATION = 600L
    const val PAGE_LOAD_TIMEOUT = 5000L
    const val SEARCH_WAIT_DELAY = 2500L
    const val RANDOM_DELAY_MAX = 500
    const val CLICK_OFFSET_MAX = 8

    // ===== 转发配置 =====
    /** 每批转发最多选多少个群（企微限制9个） */
    const val BATCH_SIZE = 9

    // ===== 存储配置 =====
    const val MESSAGE_FILE = "messages.json"
    const val FINGERPRINT_FILE = "fingerprints.json"
    const val BOOKMARK_FILE = "bookmark.json"
    const val LOG_FILE = "collector.log"
    const val USER_CONFIG_FILE = "user_config.json"

    // ===== 企业微信包名 =====
    const val WEWORK_PACKAGE = "com.tencent.wework"

    // ===== 调试 =====
    @Volatile var debug: Boolean = true

    // ===== UI 日志回调 =====
    /**
     * 所有模块统一用这个回调输出日志到 UI/悬浮窗。
     * @Volatile 保证赋值对采集协程线程立即可见。
     */
    @Volatile var uiLog: ((String) -> Unit)? = null
}
