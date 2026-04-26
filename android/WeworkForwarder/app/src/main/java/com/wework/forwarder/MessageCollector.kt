package com.wework.forwarder

import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 消息采集模块
 *
 * 从企业微信聊天页面读取消息，通过书签机制判断新消息
 * 采集策略：控件树结构 → 文本节点扫描（两级降级）
 */
object MessageCollector {
    private const val TAG = "Collector"

    /**
     * 采集当前屏幕上可见的所有消息
     */
    fun collectVisibleMessages(service: WeWorkAccessibilityService): List<Storage.Message> {
        val root = service.getRootNode()
        if (root == null) {
            Log.w(TAG, "无法获取根节点")
            return emptyList()
        }

        // 策略1：通过消息容器结构采集
        var messages = collectByStructure(root)

        if (messages.isEmpty()) {
            Log.d(TAG, "控件结构采集失败，尝试文本节点采集...")
            // 策略2：直接采集所有文本节点
            messages = collectByTextNodes(root)
        }

        Log.d(TAG, "屏幕可见 ${messages.size} 条消息")
        return messages
    }

    /**
     * 检查当前屏幕是否有新消息（对比书签）
     */
    fun hasNewMessages(service: WeWorkAccessibilityService): Boolean {
        val bookmark = Storage.getBookmark() ?: run {
            Log.d(TAG, "没有书签记录，认为有新消息")
            return true
        }

        val messages = collectVisibleMessages(service)
        val lastMsg = messages.lastOrNull() ?: run {
            Log.d(TAG, "无法读取屏幕消息")
            return false
        }

        if (Storage.matchesBookmark(lastMsg.sender, lastMsg.content)) {
            Log.d(TAG, "最后一条消息和书签一致，没有新消息")
            return false
        }

        Log.d(TAG, "发现新消息，最后一条: ${lastMsg.sender}: ${lastMsg.content}")
        return true
    }

    /**
     * 获取屏幕上最后一条消息
     */
    fun getLastMessage(service: WeWorkAccessibilityService): Storage.Message? {
        return collectVisibleMessages(service).lastOrNull()
    }

    /**
     * 在屏幕上查找书签消息
     */
    fun findBookmarkOnScreen(service: WeWorkAccessibilityService): BookmarkResult? {
        val bookmark = Storage.getBookmark() ?: return null
        val messages = collectVisibleMessages(service)
        for (i in messages.indices.reversed()) {
            if (Storage.matchesBookmark(messages[i].sender, messages[i].content)) {
                return BookmarkResult(index = i, message = messages[i], totalOnScreen = messages.size)
            }
        }
        return null
    }

    /**
     * 向上滚动查找书签消息
     */
    fun scrollUpToBookmark(service: WeWorkAccessibilityService, metrics: DisplayMetrics, maxScrolls: Int = 30): BookmarkResult? {
        for (i in 0 until maxScrolls) {
            val result = findBookmarkOnScreen(service)
            if (result != null) return result
            Log.d(TAG, "书签不在当前屏幕，继续向上滚动 (${i + 1}/$maxScrolls)")
            GestureHelper.swipeUp(service, metrics)
        }
        Log.w(TAG, "滚动 $maxScrolls 次仍未找到书签消息")
        return null
    }

    /**
     * 获取书签之后的第一条新消息
     */
    fun getFirstNewMessage(service: WeWorkAccessibilityService): Storage.Message? {
        val bookmark = Storage.getBookmark()
        val messages = collectVisibleMessages(service)
        if (messages.isEmpty()) return null

        if (bookmark == null) return messages.firstOrNull()

        for (i in messages.indices) {
            if (Storage.matchesBookmark(messages[i].sender, messages[i].content)) {
                return if (i + 1 < messages.size) messages[i + 1] else null
            }
        }
        // 书签不在当前屏幕
        return messages.firstOrNull()
    }

    /**
     * 在屏幕上找到指定消息内容对应的控件（用于长按）
     */
    fun findMessageElement(service: WeWorkAccessibilityService, msg: Storage.Message): AccessibilityNodeInfo? {
        val root = service.getRootNode() ?: return null

        // 通过消息内容文本查找
        if (msg.content.isNotEmpty() && msg.content != "[图片]" && msg.content != "[文件]") {
            NodeFinder.findByText(root, msg.content)?.let { return it }
            if (msg.content.length > 20) {
                NodeFinder.findByTextContains(root, msg.content.take(20))?.let { return it }
            }
        }

        // 通过发送人名字定位
        if (msg.sender.isNotEmpty() && msg.sender != "未知" && msg.sender != "系统") {
            val senderNode = NodeFinder.findByText(root, msg.sender)
            if (senderNode != null) {
                val parent = senderNode.parent
                if (parent != null) {
                    val texts = NodeFinder.getAllTexts(parent)
                    val contentNode = texts.firstOrNull { it.text != msg.sender }
                    if (contentNode != null) {
                        return NodeFinder.findByText(parent, contentNode.text)
                    }
                }
            }
        }
        return null
    }

    // ===== 内部采集方法 =====

    /**
     * 策略1：通过消息容器的控件结构采集
     */
    private fun collectByStructure(root: AccessibilityNodeInfo): List<Storage.Message> {
        val messages = mutableListOf<Storage.Message>()
        var currentTime = ""

        // 找聊天列表容器
        val chatList = NodeFinder.findByClassName(root, "android.widget.ListView")
            ?: NodeFinder.findByClassName(root, "androidx.recyclerview.widget.RecyclerView")
            ?: NodeFinder.findByClassName(root, "android.widget.AbsListView")
        if (chatList == null) {
            Log.d(TAG, "找不到聊天列表容器")
            return messages
        }

        val childCount = chatList.childCount
        Log.d(TAG, "聊天列表子节点数: $childCount")

        for (i in 0 until childCount) {
            val child = chatList.getChild(i) ?: continue
            val msgInfo = parseMessageNode(child, currentTime)
            if (msgInfo != null) {
                if (msgInfo.isTimeLabel) {
                    currentTime = msgInfo.time
                } else {
                    messages.add(msgInfo.toMessage())
                }
            }
        }
        return messages
    }

    /**
     * 解析单个消息节点
     */
    private fun parseMessageNode(node: AccessibilityNodeInfo, currentTime: String): ParsedNode? {
        val texts = NodeFinder.getAllTexts(node)

        if (texts.isEmpty()) {
            // 检查是否有图片
            val hasImage = NodeFinder.findByClassName(node, "android.widget.ImageView")
            if (hasImage != null) {
                return ParsedNode(sender = "未知", time = currentTime, content = "[图片]", type = "image")
            }
            return null
        }

        // 判断时间标签
        if (texts.size == 1 && isTimeLabel(texts[0].text)) {
            return ParsedNode(isTimeLabel = true, time = texts[0].text)
        }

        // 判断系统消息
        if (texts.size == 1 && isSystemMessage(texts[0].text)) {
            return ParsedNode(sender = "系统", time = currentTime, content = texts[0].text, type = "system")
        }

        // 解析发送人和内容
        val sender: String
        val content: String
        var type = "text"

        if (texts.size >= 2) {
            sender = texts[0].text
            content = texts.drop(1).joinToString(" ") { it.text }
        } else {
            sender = "未知"
            content = texts[0].text
        }

        if (content.contains("[文件]") || NodeFinder.findByDesc(node, "文件") != null) {
            type = "file"
        } else if (content.contains("[链接]") || content.contains("http")) {
            type = "link"
        } else if (content.isEmpty() && NodeFinder.findByClassName(node, "android.widget.ImageView") != null) {
            return ParsedNode(sender = sender, time = currentTime, content = "[图片]", type = "image")
        }

        if (content.isEmpty() && sender.isEmpty()) return null
        return ParsedNode(sender = sender, time = currentTime, content = content, type = type)
    }

    /**
     * 策略2：通过所有文本节点推断消息
     */
    private fun collectByTextNodes(root: AccessibilityNodeInfo): List<Storage.Message> {
        val messages = mutableListOf<Storage.Message>()
        val allTextViews = NodeFinder.findAllByClassName(root, "android.widget.TextView")
        if (allTextViews.isEmpty()) return messages

        Log.d(TAG, "屏幕上文本节点总数: ${allTextViews.size}")

        // 收集所有有文本的 TextView 及其坐标
        val textItems = allTextViews.mapNotNull { tv ->
            val text = tv.text?.toString()?.trim() ?: return@mapNotNull null
            if (text.isEmpty()) return@mapNotNull null
            val rect = Rect()
            tv.getBoundsInScreen(rect)
            NodeFinder.TextNode(text, rect)
        }.sortedBy { it.bounds.centerY() }

        // 按 Y 坐标临近度分组
        var currentTime = ""
        val groups = groupByProximity(textItems, 60)

        for (group in groups) {
            if (group.size == 1) {
                if (isTimeLabel(group[0].text)) {
                    currentTime = group[0].text
                    continue
                }
                if (isSystemMessage(group[0].text)) {
                    messages.add(Storage.Message(sender = "系统", time = currentTime, content = group[0].text, type = "system"))
                    continue
                }
            }

            if (group.size >= 2) {
                messages.add(Storage.Message(
                    sender = group[0].text,
                    time = currentTime,
                    content = group.drop(1).joinToString(" ") { it.text },
                    type = "text"
                ))
            } else if (group.size == 1) {
                messages.add(Storage.Message(
                    sender = "未知",
                    time = currentTime,
                    content = group[0].text,
                    type = "text"
                ))
            }
        }
        return messages
    }

    /**
     * 将文本节点按 Y 坐标临近度分组
     */
    private fun groupByProximity(items: List<NodeFinder.TextNode>, threshold: Int): List<List<NodeFinder.TextNode>> {
        if (items.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<NodeFinder.TextNode>>()
        var current = mutableListOf(items[0])
        for (i in 1 until items.size) {
            if (Math.abs(items[i].bounds.centerY() - items[i - 1].bounds.centerY()) < threshold) {
                current.add(items[i])
            } else {
                groups.add(current)
                current = mutableListOf(items[i])
            }
        }
        groups.add(current)
        return groups
    }

    /**
     * 判断文本是否是时间标签
     */
    private fun isTimeLabel(text: String): Boolean {
        return Regex("^\\d{1,2}:\\d{2}$").matches(text)
                || text.startsWith("昨天")
                || text.startsWith("星期")
                || Regex("^\\d{1,2}月\\d{1,2}日").containsMatchIn(text)
                || Regex("^\\d{4}[/\\-]\\d{1,2}[/\\-]\\d{1,2}").containsMatchIn(text)
                || Regex("^上午\\s*\\d").containsMatchIn(text)
                || Regex("^下午\\s*\\d").containsMatchIn(text)
    }

    /**
     * 判断是否是系统消息
     */
    private fun isSystemMessage(text: String): Boolean {
        val keywords = listOf("加入了群聊", "退出了群聊", "移出了群聊", "修改了群名", "撤回了一条消息", "你已被", "邀请了")
        return keywords.any { text.contains(it) }
    }

    // ===== 数据类 =====

    data class BookmarkResult(val index: Int, val message: Storage.Message, val totalOnScreen: Int)

    private data class ParsedNode(
        val isTimeLabel: Boolean = false,
        val sender: String = "",
        val time: String = "",
        val content: String = "",
        val type: String = "text"
    ) {
        fun toMessage() = Storage.Message(sender = sender, time = time, content = content, type = type)
    }
}
