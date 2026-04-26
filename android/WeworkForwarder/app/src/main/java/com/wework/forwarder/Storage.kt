package com.wework.forwarder

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地存储模块
 *
 * 负责消息持久化、指纹去重、书签管理、用户配置
 */
object Storage {
    private const val TAG = "Storage"
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    // 内存缓存
    private var fingerprints: MutableMap<String, String> = mutableMapOf()
    private var messages: MutableList<Message> = mutableListOf()
    private var bookmark: Bookmark? = null

    /**
     * 初始化存储目录和文件
     */
    fun init() {
        val dir = File(Config.DATA_DIR)
        if (!dir.exists()) dir.mkdirs()

        // 加载指纹
        loadFile<MutableMap<String, String>>(Config.FINGERPRINT_FILE)?.let {
            fingerprints = it
            Log.i(TAG, "已加载 ${fingerprints.size} 条指纹记录")
        }

        // 加载消息
        loadFile<MutableList<Message>>(Config.MESSAGE_FILE)?.let {
            messages = it
            Log.i(TAG, "已加载 ${messages.size} 条消息记录")
        }

        // 加载书签
        loadFile<Bookmark>(Config.BOOKMARK_FILE)?.let {
            bookmark = it
            Log.i(TAG, "已加载书签: $it")
        }

        Log.i(TAG, "存储模块初始化完成")
    }

    // ===== 书签管理 =====

    fun saveBookmark(sender: String, content: String, time: String) {
        bookmark = Bookmark(
            sender = sender,
            content = content.take(30),
            time = time,
            savedAt = now()
        )
        saveFile(Config.BOOKMARK_FILE, bookmark)
    }

    fun getBookmark(): Bookmark? = bookmark

    /**
     * 检查消息是否匹配书签
     */
    fun matchesBookmark(sender: String?, content: String?): Boolean {
        val bm = bookmark ?: return false
        return (sender ?: "") == bm.sender && (content ?: "").take(30) == bm.content
    }

    // ===== 指纹管理 =====

    fun isForwarded(fp: String): Boolean = fingerprints.containsKey(fp)

    fun markForwarded(fp: String) {
        fingerprints[fp] = now()
        saveFile(Config.FINGERPRINT_FILE, fingerprints)
    }

    /**
     * 清理过期指纹（超过7天的）
     */
    fun cleanOldFingerprints() {
        val cutoff = Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)
        val cutoffStr = dateFormat.format(cutoff)
        val before = fingerprints.size
        fingerprints.entries.removeAll { it.value < cutoffStr }
        val removed = before - fingerprints.size
        if (removed > 0) {
            Log.i(TAG, "清理了 $removed 条过期指纹")
            saveFile(Config.FINGERPRINT_FILE, fingerprints)
        }
    }

    // ===== 消息记录 =====

    fun saveMessages(msgs: List<Message>) {
        val time = now()
        msgs.forEach { it.collectTime = time }
        messages.addAll(msgs)
        saveFile(Config.MESSAGE_FILE, messages)
    }

    // ===== 用户配置 =====

    fun loadUserConfig(): Boolean {
        return try {
            val file = File(Config.DATA_DIR + Config.USER_CONFIG_FILE)
            if (!file.exists()) return false
            val config = gson.fromJson(file.readText(), UserConfig::class.java) ?: return false
            Config.sourceGroup = config.sourceGroup ?: ""
            Config.targetGroups = config.targetGroups ?: emptyList()
            Config.lookbackMinutes = config.lookbackMinutes ?: 10
            Config.pollIntervalSeconds = config.pollIntervalSeconds ?: 30
            true
        } catch (e: Exception) {
            Log.w(TAG, "加载用户配置失败: ${e.message}")
            false
        }
    }

    fun saveUserConfig() {
        val config = UserConfig(
            sourceGroup = Config.sourceGroup,
            targetGroups = Config.targetGroups,
            lookbackMinutes = Config.lookbackMinutes,
            pollIntervalSeconds = Config.pollIntervalSeconds
        )
        saveFile(Config.USER_CONFIG_FILE, config)
    }

    // ===== 工具方法 =====

    fun now(): String = dateFormat.format(Date())

    /**
     * 生成消息指纹（用于去重）
     */
    fun fingerprint(sender: String?, time: String?, content: String?): String {
        val key = "${sender ?: ""}|${time ?: ""}|${(content ?: "").take(30)}"
        var hash = 0
        for (ch in key) {
            hash = ((hash shl 5) - hash) + ch.code
            hash = hash and hash // 转为 32 位整数
        }
        return hash.toString(36)
    }

    private inline fun <reified T> loadFile(filename: String): T? {
        return try {
            val file = File(Config.DATA_DIR + filename)
            if (!file.exists()) return null
            gson.fromJson(file.readText(), object : TypeToken<T>() {}.type)
        } catch (e: Exception) {
            Log.w(TAG, "加载 $filename 失败: ${e.message}")
            null
        }
    }

    private fun saveFile(filename: String, data: Any?) {
        try {
            val file = File(Config.DATA_DIR + filename)
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(data))
        } catch (e: Exception) {
            Log.e(TAG, "保存 $filename 失败: ${e.message}")
        }
    }

    // ===== 数据类 =====

    data class Bookmark(
        val sender: String = "",
        val content: String = "",
        val time: String = "",
        val savedAt: String = ""
    )

    data class Message(
        val sender: String = "",
        val time: String = "",
        val content: String = "",
        val type: String = "text",
        var collectTime: String = ""
    )

    data class UserConfig(
        val sourceGroup: String? = null,
        val targetGroups: List<String>? = null,
        val lookbackMinutes: Int? = null,
        val pollIntervalSeconds: Int? = null
    )
}
