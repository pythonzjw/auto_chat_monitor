package com.wework.forwarder

import android.content.Context
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
 * 使用 App 内部存储（context.filesDir），无需额外存储权限
 */
object Storage {
    private const val TAG = "Storage"
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    // 内存缓存
    private var fingerprints: MutableMap<String, String> = mutableMapOf()
    private var messages: MutableList<Message> = mutableListOf()
    private var bookmark: Bookmark? = null

    /** App 内部存储根目录，由 init(context) 设置 */
    private lateinit var dataDir: File

    /**
     * 初始化存储（必须传入 Context）
     */
    fun init(context: Context) {
        dataDir = File(context.filesDir, "wework-collector")
        if (!dataDir.exists()) dataDir.mkdirs()
        Log.i(TAG, "存储目录: ${dataDir.absolutePath}")

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

    /**
     * 兼容旧的无参 init()
     */
    fun init() {
        if (!isInitialized()) {
            Log.w(TAG, "Storage.init() 未传入 Context，跳过")
        }
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

    fun matchesBookmark(sender: String?, content: String?, time: String?): Boolean {
        val bm = bookmark ?: return false
        val c = (content ?: "").take(30)
        // 精确匹配：sender + content + time
        if ((sender ?: "") == bm.sender && c == bm.content) {
            if (bm.time.isEmpty() || (time ?: "") == bm.time) return true
        }
        // 放宽匹配：content + time（sender 可能因版本变化）
        if (c.isNotEmpty() && c == bm.content) {
            if (bm.time.isEmpty() || (time ?: "") == bm.time) {
                Log.d(TAG, "书签宽松匹配: sender不同(${sender} vs ${bm.sender})，但content一致")
                return true
            }
        }
        return false
    }

    // ===== 指纹管理 =====

    fun isForwarded(fp: String): Boolean = fingerprints.containsKey(fp)

    fun markForwarded(fp: String) {
        fingerprints[fp] = now()
        saveFile(Config.FINGERPRINT_FILE, fingerprints)
    }

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

    fun loadUserConfig(context: Context): Boolean {
        if (!isInitialized()) {
            dataDir = File(context.filesDir, "wework-collector")
            if (!dataDir.exists()) dataDir.mkdirs()
        }
        return try {
            val file = File(dataDir, Config.USER_CONFIG_FILE)
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

    /** 兼容旧的无参版本 */
    fun loadUserConfig(): Boolean {
        if (!isInitialized()) return false
        return try {
            val file = File(dataDir, Config.USER_CONFIG_FILE)
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

    // ===== 日志文件 =====

    /**
     * 追加一行日志到文件
     */
    fun appendLogLine(line: String) {
        try {
            if (!isInitialized()) return
            val file = File(dataDir, Config.LOG_FILE)
            file.appendText(line + "\n")
            // 日志文件超过 500KB 时截断保留后半部分
            if (file.length() > 500 * 1024) {
                val lines = file.readLines()
                val keep = lines.takeLast(lines.size / 2)
                file.writeText(keep.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 读取最近的日志行
     */
    fun readRecentLogs(maxLines: Int = 100): List<String> {
        return try {
            if (!isInitialized()) return emptyList()
            val file = File(dataDir, Config.LOG_FILE)
            if (!file.exists()) return emptyList()
            file.readLines().takeLast(maxLines)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 保存 dump 文件（诊断用）
     */
    fun saveDump(filename: String, content: String): String? {
        return try {
            if (!isInitialized()) return null
            val file = File(dataDir, filename)
            file.writeText(content)
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存 dump 失败: ${e.message}")
            null
        }
    }

    // ===== 工具方法 =====

    fun now(): String = dateFormat.format(Date())

    fun fingerprint(sender: String?, time: String?, content: String?): String {
        val key = "${sender ?: ""}|${time ?: ""}|${(content ?: "").take(30)}"
        var hash = 0
        for (ch in key) {
            hash = ((hash shl 5) - hash) + ch.code
            hash = hash and hash
        }
        return hash.toString(36)
    }

    fun getDataDir(): File? = if (isInitialized()) dataDir else null

    /** 检查 dataDir 是否已初始化（供 inline 函数安全调用） */
    fun isInitialized(): Boolean = ::dataDir.isInitialized

    private inline fun <reified T> loadFile(filename: String): T? {
        return try {
            if (!isInitialized()) return null
            val file = File(dataDir, filename)
            if (!file.exists()) return null
            gson.fromJson(file.readText(), object : TypeToken<T>() {}.type)
        } catch (e: Exception) {
            Log.w(TAG, "加载 $filename 失败: ${e.message}")
            null
        }
    }

    private fun saveFile(filename: String, data: Any?) {
        try {
            if (!isInitialized()) return
            val file = File(dataDir, filename)
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
