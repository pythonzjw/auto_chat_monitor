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

    private fun log(msg: String) {
        Log.d(TAG, msg)
        Config.uiLog?.invoke(msg)
    }

    /**
     * 采集当前屏幕上可见的所有消息
     */
    fun collectVisibleMessages(service: WeWorkAccessibilityService): List<Storage.Message> {
        val root = service.getRootNode()
        if (root == null) {
            log("[采集] 无法获取根节点")
            return emptyList()
        }

        // 获取屏幕尺寸（用于限定扫描范围）
        val screenWidth = service.resources.displayMetrics.widthPixels
        val screenHeight = service.resources.displayMetrics.heightPixels

        // 策略1：通过消息容器结构采集
        var messages = collectByStructure(root)

        if (messages.isEmpty()) {
            if (Config.debug) log("[采集] 控件结构采集失败，尝试文本节点采集...")
            // 策略2：直接采集所有文本节点（限定范围）
            messages = collectByTextNodes(root, screenWidth, screenHeight)
        }

        // 输出采集摘要
        log("[采集] 屏幕可见 ${messages.size} 条消息")
        for ((i, msg) in messages.withIndex()) {
            if (i < 5 || i >= messages.size - 2) {
                log("[采集] #${i + 1} ${msg.sender}: ${msg.content.take(30)}")
            } else if (i == 5) {
                log("[采集] ... 省略中间 ${messages.size - 7} 条 ...")
            }
        }

        return messages
    }

    /**
     * 检查当前屏幕是否有新消息（对比书签）
     */
    fun hasNewMessages(service: WeWorkAccessibilityService): Boolean {
        val bookmark = Storage.getBookmark() ?: run {
            log("[采集] 没有书签，认为有新消息")
            return true
        }

        val messages = collectVisibleMessages(service)
        val lastMsg = messages.lastOrNull() ?: run {
            log("[采集] 无法读取屏幕消息")
            return false
        }

        if (Storage.matchesBookmark(lastMsg.sender, lastMsg.content)) {
            if (Config.debug) Log.d(TAG, "最后一条和书签一致，没有新消息")
            return false
        }

        log("[采集] 发现新消息")
        return true
    }

    fun getLastMessage(service: WeWorkAccessibilityService): Storage.Message? {
        return collectVisibleMessages(service).lastOrNull()
    }

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

    fun scrollUpToBookmark(service: WeWorkAccessibilityService, metrics: DisplayMetrics, maxScrolls: Int = 30): BookmarkResult? {
        for (i in 0 until maxScrolls) {
            if (!CollectorService.isRunning) return null
            val result = findBookmarkOnScreen(service)
            if (result != null) return result
            GestureHelper.swipeUp(service, metrics)
        }
        log("[采集] 滚动 $maxScrolls 次未找到书签")
        return null
    }

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
        return messages.firstOrNull()
    }

    /**
     * 在屏幕上找到指定消息内容对应的控件（用于长按）
     */
    fun findMessageElement(service: WeWorkAccessibilityService, msg: Storage.Message): AccessibilityNodeInfo? {
        val root = service.getRootNode() ?: return null

        // 通过消息内容文本查找
        if (msg.content.isNotEmpty() && msg.content != "[图片]" && msg.content != "[文件]") {
            NodeFinder.findByText(root, msg.content)?.let {
                log("[采集] 通过内容定位到控件")
                return it
            }
            if (msg.content.length > 20) {
                NodeFinder.findByTextContains(root, msg.content.take(20))?.let {
                    log("[采集] 通过内容(前20字)定位到控件")
                    return it
                }
            }
        }

        // 通过发送人名字定位
        if (msg.sender.isNotEmpty() && msg.sender != "未知" && msg.sender != "系统" && msg.sender != "我") {
            val senderNode = NodeFinder.findByText(root, msg.sender)
            if (senderNode != null) {
                val parent = senderNode.parent
                if (parent != null) {
                    val texts = NodeFinder.getAllTexts(parent)
                    val contentNode = texts.firstOrNull { it.text != msg.sender }
                    if (contentNode != null) {
                        log("[采集] 通过发送人定位到控件")
                        return NodeFinder.findByText(parent, contentNode.text)
                    }
                }
            }
        }

        log("[采集] ✗ 无法定位控件 (${msg.sender}: ${msg.content.take(20)})")
        return null
    }

    // ===== 内部采集方法 =====

    /**
     * 策略1：通过消息容器的控件结构采集
     */
    private fun collectByStructure(root: AccessibilityNodeInfo): List<Storage.Message> {
        val messages = mutableListOf<Storage.Message>()
        var currentTime = ""

        val chatList = NodeFinder.findByClassName(root, "android.widget.ListView")
            ?: NodeFinder.findByClassName(root, "androidx.recyclerview.widget.RecyclerView")
            ?: NodeFinder.findByClassName(root, "android.widget.AbsListView")
        if (chatList == null) {
            if (Config.debug) Log.d(TAG, "找不到聊天列表容器")
            return messages
        }

        val childCount = chatList.childCount
        for (i in 0 until childCount) {
            val child = chatList.getChild(i) ?: continue
            val msgInfo = parseMessageNode(child, currentTime)
            if (msgInfo != null) {
                if (msgInfo.isTimeLabel) currentTime = msgInfo.time
                else messages.add(msgInfo.toMessage())
            }
        }
        return messages
    }

    private fun parseMessageNode(node: AccessibilityNodeInfo, currentTime: String): ParsedNode? {
        val texts = NodeFinder.getAllTexts(node)

        if (texts.isEmpty()) {
            val hasImage = NodeFinder.findByClassName(node, "android.widget.ImageView")
            if (hasImage != null) return ParsedNode(sender = "未知", time = currentTime, content = "[图片]", type = "image")
            return null
        }

        if (texts.size == 1 && isTimeLabel(texts[0].text))
            return ParsedNode(isTimeLabel = true, time = texts[0].text)

        if (texts.size == 1 && isSystemMessage(texts[0].text))
            return ParsedNode(sender = "系统", time = currentTime, content = texts[0].text, type = "system")

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

        if (content.contains("[文件]") || NodeFinder.findByDesc(node, "文件") != null) type = "file"
        else if (content.contains("[链接]") || content.contains("http")) type = "link"
        else if (content.isEmpty() && NodeFinder.findByClassName(node, "android.widget.ImageView") != null)
            return ParsedNode(sender = sender, time = currentTime, content = "[图片]", type = "image")

        if (content.isEmpty() && sender.isEmpty()) return null
        return ParsedNode(sender = sender, time = currentTime, content = content, type = type)
    }

    /**
     * 策略2：通过所有文本节点推断消息（限定屏幕范围）
     */
    private fun collectByTextNodes(root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): List<Storage.Message> {
        val messages = mutableListOf<Storage.Message>()
        val allTextViews = NodeFinder.findAllByClassName(root, "android.widget.TextView")
        if (allTextViews.isEmpty()) {
            log("[采集] 屏幕上没有 TextView")
            return messages
        }

        // 限定扫描范围：排除标题栏(上方15%)和底部输入区(下方12%)
        val yMin = (screenHeight * 0.15).toInt()
        val yMax = (screenHeight * 0.88).toInt()
        val halfWidth = screenWidth / 2

        val textItems = allTextViews.mapNotNull { tv ->
            val text = tv.text?.toString()?.trim() ?: return@mapNotNull null
            if (text.isEmpty()) return@mapNotNull null
            val rect = Rect()
            tv.getBoundsInScreen(rect)
            // 过滤掉标题栏和底部区域的文本
            if (rect.centerY() < yMin || rect.centerY() > yMax) return@mapNotNull null
            // 过滤掉明显不是消息的文本
            if (isUiElement(text)) return@mapNotNull null
            NodeFinder.TextNode(text, rect)
        }.sortedBy { it.bounds.centerY() }

        if (Config.debug) Log.d(TAG, "有效文本节点: ${textItems.size} (屏幕范围 Y:$yMin-$yMax)")

        // 按 Y 坐标分组
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

            // 根据 X 坐标判断是自己发的（右侧）还是别人发的（左侧）
            val avgX = group.map { it.bounds.centerX() }.average().toInt()
            val isRightSide = avgX > halfWidth

            if (group.size >= 2) {
                if (isRightSide) {
                    // 右侧消息：自己发的，没有发送人名字
                    // 所有文本都是消息内容
                    messages.add(Storage.Message(
                        sender = "我",
                        time = currentTime,
                        content = group.joinToString(" ") { it.text },
                        type = "text"
                    ))
                } else {
                    // 左侧消息：第一个文本是发送人
                    messages.add(Storage.Message(
                        sender = group[0].text,
                        time = currentTime,
                        content = group.drop(1).joinToString(" ") { it.text },
                        type = "text"
                    ))
                }
            } else if (group.size == 1) {
                messages.add(Storage.Message(
                    sender = if (isRightSide) "我" else "未知",
                    time = currentTime,
                    content = group[0].text,
                    type = "text"
                ))
            }
        }
        return messages
    }

    /**
     * 判断是否是 UI 元素而非消息文本
     */
    private fun isUiElement(text: String): Boolean {
        // 过滤群名+成员数格式、常见按钮文字等
        val uiTexts = setOf("消息", "通讯录", "工作台", "我", "发送", "更多", "返回",
            "表情", "语音", "文件", "拍摄", "位置", "视频通话", "语音通话")
        if (text in uiTexts) return true
        // 群名(N) 格式
        if (Regex("^.+\\(\\d+\\)$").matches(text)) return true
        return false
    }

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

    private fun isTimeLabel(text: String): Boolean {
        return Regex("^\\d{1,2}:\\d{2}$").matches(text)
                || text.startsWith("昨天")
                || text.startsWith("星期")
                || Regex("^\\d{1,2}月\\d{1,2}日").containsMatchIn(text)
                || Regex("^\\d{4}[/\\-]\\d{1,2}[/\\-]\\d{1,2}").containsMatchIn(text)
                || Regex("^上午\\s*\\d").containsMatchIn(text)
                || Regex("^下午\\s*\\d").containsMatchIn(text)
    }

    private fun isSystemMessage(text: String): Boolean {
        val keywords = listOf("加入了群聊", "退出了群聊", "移出了群聊", "修改了群名",
            "撤回了一条消息", "你已被", "邀请了", "拒绝")
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
