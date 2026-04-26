package com.wework.forwarder

/**
 * 全局配置
 */
object Config {
    // ===== 群配置 =====
    var sourceGroup: String = ""
    var targetGroups: List<String> = emptyList()

    // ===== 时间配置 =====
    var lookbackMinutes: Int = 10
    var enterGroupWaitSeconds: Int = 3
    var pollIntervalSeconds: Int = 30

    // ===== 操作延时（毫秒） =====
    const val CLICK_DELAY = 800L
    const val SWIPE_DELAY = 1000L
    const val LONG_PRESS_DURATION = 600L
    const val PAGE_LOAD_TIMEOUT = 5000L
    const val SEARCH_WAIT_DELAY = 1500L
    const val RANDOM_DELAY_MAX = 500
    const val CLICK_OFFSET_MAX = 8

    // ===== 存储配置 =====
    const val DATA_DIR = "/sdcard/wework-collector/"
    const val MESSAGE_FILE = "messages.json"
    const val FINGERPRINT_FILE = "fingerprints.json"
    const val BOOKMARK_FILE = "bookmark.json"
    const val LOG_FILE = "collector.log"
    const val USER_CONFIG_FILE = "user_config.json"

    // ===== 企业微信包名 =====
    const val WEWORK_PACKAGE = "com.tencent.wework"

    // ===== 调试 =====
    var debug: Boolean = true
}
