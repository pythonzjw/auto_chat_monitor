package com.wework.forwarder

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 本地存储模块
 *
 * 使用 App 内部存储（context.filesDir），无需额外存储权限。
 * 文件 IO 统一切换到单线程后台 Executor，不阻塞主线程/协程。
 */
object Storage {
    private const val TAG = "Storage"
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    /** 单线程 IO Executor，所有文件写操作在此线程串行执行，避免并发写冲突 */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "storage-io").also { it.isDaemon = true }
    }

    // 内存缓存（读操作直接走内存，写操作异步落盘）
    @Volatile private var fingerprints: MutableMap<String, Long> = mutableMapOf()
    @Volatile private var messages: MutableList<Message> = mutableListOf()
    @Volatile private var bookmark: Bookmark? = null

    // saveMessages 的临界区锁：保护 messages 字段的 add/截断/快照三步原子性
    private val messagesLock = Any()

    // appendLogLine 的截断频率计数器（仅在 ioExecutor 单线程更新，无需同步）
    private var logAppendCount = 0
    private const val LOG_CHECK_INTERVAL = 100

    /** App 内部存储根目录，由 init(context) 设置 */
    private lateinit var dataDir: File

    // ===== 最大消息记录数（防止 messages.json 无限增长） =====
    private const val MAX_MESSAGES = 2000

    /**
     * 初始化存储（必须传入 Context）
     */
    fun init(context: Context) {
        dataDir = File(context.filesDir, "wework-collector")
        if (!dataDir.exists()) dataDir.mkdirs()
        Log.i(TAG, "存储目录: ${dataDir.absolutePath}")

        // 加载指纹（时间戳格式，兼容旧字符串格式）
        try {
            val file = File(dataDir, Config.FINGERPRINT_FILE)
            if (file.exists()) {
                // 先尝试新格式（Map<String, Long>）
                val newType = object : TypeToken<MutableMap<String, Long>>() {}.type
                val loaded: MutableMap<String, Long>? = tryParse(file.readText(), newType)
                if (loaded != null) {
                    fingerprints = loaded
                } else {
                    // 兼容旧格式（Map<String, String>）：迁移时用当前时间戳，
                    // 让 cleanOldFingerprints 能在 7 天后正常清理（旧用 0L 永不清理）
                    val oldType = object : TypeToken<MutableMap<String, String>>() {}.type
                    val oldMap: MutableMap<String, String>? = tryParse(file.readText(), oldType)
                    if (oldMap != null) {
                        val migrationTs = System.currentTimeMillis()
                        fingerprints = oldMap.mapValues { migrationTs }.toMutableMap()
                        Log.i(TAG, "已迁移旧格式指纹 ${fingerprints.size} 条")
                    }
                }
                Log.i(TAG, "已加载 ${fingerprints.size} 条指纹记录")
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载指纹失败: ${e.message}")
        }

        // 加载消息
        loadFile<MutableList<Message>>(Config.MESSAGE_FILE)?.let {
            messages = it
            Log.i(TAG, "已加载 ${messages.size} 条消息记录")
        }

        // 加载书签
        loadFile<Bookmark>(Config.BOOKMARK_FILE)?.let {
            bookmark = it
            Log.i(TAG, "已加载书签: sender=${it.sender}, content=${it.content.take(20)}")
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

    fun saveBookmark(
        sender: String,
        content: String,
        time: String,
        prevSender: String = "",
        prevContent: String = "",
        totalParsed: Int = 0
    ) {
        val bm = Bookmark(
            sender = sender,
            content = content.take(30),
            time = time,
            savedAt = now(),
            prevSender = prevSender,
            prevContent = prevContent.take(30),
            totalParsed = totalParsed
        )
        bookmark = bm
        ioExecutor.execute { saveFile(Config.BOOKMARK_FILE, bm) }
    }

    fun getBookmark(): Bookmark? = bookmark

    /**
     * 精确匹配书签：sender + content 均需相同。
     * 宽松匹配（仅 content）已去除，避免重复内容导致的假阳性误判。
     * 时间字段做包含匹配（企微"昨天 23:45"→"23:45"跨天问题）。
     */
    fun matchesBookmark(sender: String?, content: String?, time: String?): Boolean {
        val bm = bookmark ?: return false
        val c = (content ?: "").take(30)
        val s = sender ?: ""

        // 主匹配：sender + content 均需匹配
        if (s == bm.sender && c == bm.content) return true

        // 不再做宽松匹配（仅比较 content），避免群里多人发相同内容时误判
        return false
    }

    // ===== 指纹管理 =====

    /**
     * 计算消息指纹（SHA-256 前16字符，保证唯一性）
     */
    fun fingerprint(sender: String?, time: String?, content: String?): String {
        val key = "${sender ?: ""}|${time ?: ""}|${(content ?: "").take(30)}"
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(key.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (_: Exception) {
            // 降级：djb2（纠正原有写法中 `hash and hash` 的无效操作）
            var hash = 5381L
            for (ch in key) {
                hash = ((hash shl 5) + hash) + ch.code.toLong()
                hash = hash and 0xFFFFFFFFL  // 保持32位无符号
            }
            hash.toString(36)
        }
    }

    fun isForwarded(fp: String): Boolean = fingerprints.containsKey(fp)

    fun markForwarded(fp: String) {
        fingerprints[fp] = System.currentTimeMillis()
        val snapshot = fingerprints.toMap()
        ioExecutor.execute { saveFile(Config.FINGERPRINT_FILE, snapshot) }
    }

    fun cleanOldFingerprints() {
        val cutoffMs = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val before = fingerprints.size
        fingerprints.entries.removeAll { it.value < cutoffMs && it.value > 0 }
        val removed = before - fingerprints.size
        if (removed > 0) {
            Log.i(TAG, "清理了 $removed 条过期指纹")
            val snapshot = fingerprints.toMap()
            ioExecutor.execute { saveFile(Config.FINGERPRINT_FILE, snapshot) }
        }
    }

    // ===== 消息记录 =====

    fun saveMessages(msgs: List<Message>) {
        val time = now()
        msgs.forEach { it.collectTime = time }
        // add → 截断 → 快照 三步必须原子，避免与并发的 saveMessages 交错
        // 导致 ioExecutor 序列化的 list 与内存不一致
        val snapshot = synchronized(messagesLock) {
            messages.addAll(msgs)
            if (messages.size > MAX_MESSAGES) {
                messages = messages.takeLast(MAX_MESSAGES / 2).toMutableList()
                Log.i(TAG, "消息记录超出 $MAX_MESSAGES 条，已截断至 ${messages.size} 条")
            }
            messages.toList()
        }
        ioExecutor.execute { saveFile(Config.MESSAGE_FILE, snapshot) }
    }

    // ===== 用户配置 =====

    fun loadUserConfig(context: Context): Boolean {
        if (!isInitialized()) {
            dataDir = File(context.filesDir, "wework-collector")
            if (!dataDir.exists()) dataDir.mkdirs()
        }
        return loadUserConfigInternal()
    }

    fun loadUserConfig(): Boolean {
        if (!isInitialized()) return false
        return loadUserConfigInternal()
    }

    private fun loadUserConfigInternal(): Boolean {
        return try {
            val file = File(dataDir, Config.USER_CONFIG_FILE)
            if (!file.exists()) return false
            val config = gson.fromJson(file.readText(), UserConfig::class.java) ?: return false
            // null 表示字段未保存过（保持默认）；非 null（含空字符串/空 list）一律覆盖，
            // 让用户主动清空操作能生效。
            // targetGroups 拷贝成不可变 list，避免 Gson 的 ArrayList 被外部修改导致 CME。
            config.sourceGroup?.let { Config.sourceGroup = it }
            config.targetGroups?.let { Config.targetGroups = it.toList() }
            config.lookbackMinutes?.let { if (it > 0) Config.lookbackMinutes = it }
            config.pollIntervalSeconds?.let { if (it > 0) Config.pollIntervalSeconds = it }
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
        ioExecutor.execute { saveFile(Config.USER_CONFIG_FILE, config) }
    }

    // ===== 日志文件 =====

    /**
     * 追加一行日志到文件（异步 IO，不阻塞调用方）
     */
    fun appendLogLine(line: String) {
        ioExecutor.execute {
            try {
                if (!isInitialized()) return@execute
                val file = File(dataDir, Config.LOG_FILE)
                file.appendText(line + "\n")
                // 每写满 LOG_CHECK_INTERVAL 行才检查一次大小，避免每条日志都
                // 触发 file.length() + readLines 全文件 IO 抖动
                logAppendCount++
                if (logAppendCount >= LOG_CHECK_INTERVAL) {
                    logAppendCount = 0
                    if (file.length() > 500 * 1024) {
                        val lines = file.readLines()
                        val keep = lines.takeLast(lines.size / 2)
                        file.writeText(keep.joinToString("\n") + "\n")
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 读取最近的日志行（同步，仅供诊断用途）
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
     * 保存 dump 文件（诊断用，异步写）
     */
    fun saveDump(filename: String, content: String): String? {
        if (!isInitialized()) return null
        val file = File(dataDir, filename)
        ioExecutor.execute {
            try {
                file.writeText(content)
            } catch (e: Exception) {
                Log.e(TAG, "保存 dump 失败: ${e.message}")
            }
        }
        return file.absolutePath
    }

    // ===== 工具方法 =====

    fun now(): String = dateFormat.format(Date())

    fun getDataDir(): File? = if (isInitialized()) dataDir else null

    fun isInitialized(): Boolean = ::dataDir.isInitialized

    private fun <T> tryParse(json: String, type: java.lang.reflect.Type): T? {
        return try {
            gson.fromJson(json, type)
        } catch (_: Exception) {
            null
        }
    }

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
        val savedAt: String = "",
        val prevSender: String = "",
        val prevContent: String = "",
        val totalParsed: Int = 0
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
