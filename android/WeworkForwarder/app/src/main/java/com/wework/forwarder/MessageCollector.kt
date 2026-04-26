package com.wework.forwarder

import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 消息采集模块
 *
 * 基于企业微信实际控件树结构解析消息（从 dump 逆向）。
 *
 * ListView 下每个子节点（RelativeLayout）可能是：
 * - 空占位（高度极小，无文本）→ 跳过
 * - 时间标签 / 系统消息（居中文本，无头像占位符）→ 跳过，但记录时间
 * - 普通消息（头像占位 TextView " " + FrameLayout>TextView 内容）
 *   通过头像 x 坐标判断：centerX < halfWidth → 别人 / >= halfWidth → 我
 * - 图片消息（头像占位 + 大 ImageView + 无内容文本）
 */
object MessageCollector {
    private const val TAG = "Collector"

    private fun log(msg: String) {
        Log.d(TAG, msg)
        Config.uiLog?.invoke(msg)
    }

    // ===== 公开方法 =====

    /**
     * 采集当前屏幕上可见的普通消息（不含系统消息/时间标签）
     */
    fun collectVisibleMessages(service: WeWorkAccessibilityService): List<Storage.Message> {
        val root = service.getRootNode()
        if (root == null) {
            log("[采集] 无法获取根节点")
            return emptyList()
        }

        val screenWidth = service.resources.displayMetrics.widthPixels
        var messages = collectByStructure(root, screenWidth)

        if (messages.isEmpty()) {
            log("[采集] 控件结构采集失败，尝试文本节点采集...")
            val screenHeight = service.resources.displayMetrics.heightPixels
            messages = collectByTextNodes(root, screenWidth, screenHeight)
        }

        // 采集摘要
        log("[采集] 屏幕可见 ${messages.size} 条消息")
        for ((i, msg) in messages.withIndex()) {
            if (i < 3 || i >= messages.size - 2) {
                log("[采集]  #${i + 1} ${msg.sender}: ${msg.content.take(30)}")
            } else if (i == 3) {
                log("[采集]  ... 省略 ${messages.size - 5} 条 ...")
            }
        }

        return messages
    }

    fun hasNewMessages(service: WeWorkAccessibilityService): Boolean {
        val bookmark = Storage.getBookmark() ?: run {
            log("[采集] 没有书签，认为有新消息")
            return true
        }
        val messages = collectVisibleMessages(service)
        if (messages.isEmpty()) {
            log("[采集] 无法读取屏幕消息")
            return false
        }

        // 在消息列表中从后往前找书签位置
        for (i in messages.indices.reversed()) {
            if (Storage.matchesBookmark(messages[i].sender, messages[i].content)) {
                if (i < messages.size - 1) {
                    // 书签不是最后一条 → 书签后面有新消息
                    log("[采集] 书签在位置 ${i+1}/${messages.size}，后面有 ${messages.size - 1 - i} 条新消息")
                    return true
                } else {
                    // 书签是最后一条 → 没有新消息
                    log("[采集] 书签是最后一条(${messages[i].content.take(20)})，暂无新消息")
                    return false
                }
            }
        }

        // 屏幕上找不到书签 → 认为有新消息（书签已被新消息推出屏幕）
        log("[采集] 屏幕上找不到书签(${bookmark.content.take(20)})，最后一条: ${messages.last().content.take(20)}，认为有新消息")
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
            // 安全检查：滑动后确认还在聊天页面（ListView 存在）
            if (!isChatPageVisible(service)) {
                log("[采集] 滑动后已离开聊天页面，停止回溯")
                return null
            }
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
     * 在屏幕上找到消息内容对应的控件（用于长按）
     */
    fun findMessageElement(service: WeWorkAccessibilityService, msg: Storage.Message): AccessibilityNodeInfo? {
        val root = service.getRootNode() ?: return null

        // 通过消息内容文本查找（优先精确匹配）
        if (msg.content.isNotEmpty() && msg.content != "[图片]" && msg.content != "[文件]") {
            // 企微消息文本末尾可能有空格，尝试带空格匹配
            NodeFinder.findByText(root, msg.content)?.let {
                log("[采集] 通过内容定位到控件")
                return it
            }
            NodeFinder.findByText(root, "${msg.content} ")?.let {
                log("[采集] 通过内容(带尾空格)定位到控件")
                return it
            }
            if (msg.content.length > 15) {
                NodeFinder.findByTextContains(root, msg.content.take(15))?.let {
                    log("[采集] 通过内容(前15字)定位到控件")
                    return it
                }
            }
        }

        // 通过发送人名字定位（多人群中别人的消息有名字 TextView）
        if (msg.sender.isNotEmpty() && msg.sender != "未知" && msg.sender != "系统" && msg.sender != "我") {
            val senderNode = NodeFinder.findByText(root, msg.sender)
            if (senderNode != null) {
                val parent = senderNode.parent
                if (parent != null) {
                    val texts = NodeFinder.getAllTexts(parent)
                    val contentNode = texts.firstOrNull { it.text != msg.sender && it.text.trim() != "" }
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

    // ===== 策略1：基于 ListView 控件结构采集 =====

    private fun collectByStructure(root: AccessibilityNodeInfo, screenWidth: Int): List<Storage.Message> {
        val messages = mutableListOf<Storage.Message>()
        val halfWidth = screenWidth / 2

        val chatList = NodeFinder.findByClassName(root, "android.widget.ListView")
        if (chatList == null) {
            // 始终打印，便于诊断为什么策略1失败
            log("[策略1] 找不到 ListView，根包名=${root.packageName}, class=${root.className}")
            return messages
        }

        var currentTime = ""
        val childCount = chatList.childCount
        log("[策略1] 找到 ListView，childCount=$childCount")
        var skipCount = 0
        var timeCount = 0
        for (i in 0 until childCount) {
            val child = chatList.getChild(i) ?: continue
            val result = parseListItem(child, halfWidth, currentTime)
            when (result) {
                is ParseResult.TimeLabel -> { currentTime = result.time; timeCount++ }
                is ParseResult.Msg -> messages.add(result.message)
                is ParseResult.Skip -> skipCount++
            }
        }
        log("[策略1] 解析结果: ${messages.size}条消息, ${timeCount}个时间, ${skipCount}个跳过")
        return messages
    }

    /**
     * 解析 ListView 的一个子节点
     *
     * 从真实 dump (v1.5.5) 分析的结构规律：
     *
     * 1. 空占位/分隔线：高度极小（<30px），无子节点
     * 2. 时间标签+系统消息（可能在同一个 child）：
     *    - 无头像占位符（text=" " 的 TextView）
     *    - 时间如 "0:29"、"13:12" 居中显示
     *    - 系统消息如 "你邀请xxx加入了群聊" 居中显示
     * 3. 普通消息：
     *    - 有头像占位符 TextView(text=" ", bounds 约 37x37)
     *    - 消息内容在 FrameLayout > TextView 中
     *    - 我的消息：头像 centerX >= halfWidth（右侧）
     *    - 别人的消息：头像 centerX < halfWidth（左侧）
     *    - 别人消息的头像 bounds.left 很小（如 82），我的很大（如 660+）
     * 4. 图片消息：有头像 + 大 ImageView + 无内容文本
     */
    private fun parseListItem(node: AccessibilityNodeInfo, halfWidth: Int, currentTime: String): ParseResult {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // 高度太小 → 分隔线/空占位
        if (rect.height() < 30) return ParseResult.Skip

        // 收集所有文本节点（含坐标）
        val allTexts = NodeFinder.getAllTexts(node)

        // 头像占位符：text 仅含空白且尺寸合理（30-120px）
        val avatarPlaceholders = allTexts.filter {
            it.text.trim().isEmpty()
                    && it.bounds.width() in 20..150
                    && it.bounds.height() in 20..150
        }
        val contentTexts = allTexts.filter { it.text.trim().isNotEmpty() }

        if (contentTexts.isEmpty()) {
            // 没有文本内容，检查是否有图片
            val hasLargeImage = findLargeImage(node, 150)
            if (hasLargeImage && avatarPlaceholders.isNotEmpty()) {
                val avatarX = avatarPlaceholders.first().bounds.centerX()
                val sender = if (avatarX < halfWidth) "群成员" else "我"
                return ParseResult.Msg(Storage.Message(sender = sender, time = currentTime, content = "[图片]", type = "image"))
            }
            return ParseResult.Skip
        }

        // 没有头像占位符 → 时间标签 和/或 系统消息
        if (avatarPlaceholders.isEmpty()) {
            var foundTime: String? = null
            var isSystem = false
            for (tv in contentTexts) {
                if (isTimeLabel(tv.text)) {
                    foundTime = tv.text
                } else if (isSystemMessage(tv.text)) {
                    isSystem = true
                }
            }
            // 如果有时间标签就返回 TimeLabel（即使同时有系统消息）
            if (foundTime != null) return ParseResult.TimeLabel(foundTime)
            // 纯系统消息 → 跳过
            if (isSystem) return ParseResult.Skip
            // 既不是时间也不是已知系统消息 → 也跳过（居中无头像的文本都是系统类）
            return ParseResult.Skip
        }

        // 有头像 → 普通消息
        val avatarX = avatarPlaceholders.first().bounds.centerX()
        val isMe = avatarX >= halfWidth

        // 从 FrameLayout > TextView 提取消息内容
        val msgContent = findMessageContent(node)

        if (msgContent != null) {
            val content = msgContent.trimEnd()

            // 别人的消息：检查是否有发送人名字（在消息气泡上方）
            var sender = if (isMe) "我" else "群成员"
            if (!isMe) {
                val msgContentRect = findMessageContentBounds(node)
                for (tv in contentTexts) {
                    if (tv.text == content || tv.text == "$content ") continue
                    if (isTimeLabel(tv.text)) continue
                    if (isSystemMessage(tv.text)) continue
                    // 名字通常在内容上方
                    if (msgContentRect != null && tv.bounds.bottom <= msgContentRect.top + 10) {
                        sender = tv.text.trim()
                        break
                    }
                }
            }

            val type = when {
                content.contains("http") -> "link"
                else -> "text"
            }
            return ParseResult.Msg(Storage.Message(sender = sender, time = currentTime, content = content, type = type))
        }

        // 有头像但没有文本内容 → 可能是图片/文件消息
        val hasLargeImage = findLargeImage(node, 150)
        if (hasLargeImage) {
            val sender = if (isMe) "我" else "群成员"
            return ParseResult.Msg(Storage.Message(sender = sender, time = currentTime, content = "[图片]", type = "image"))
        }

        return ParseResult.Skip
    }

    /**
     * 在节点中查找 FrameLayout > TextView 的消息内容文本
     * 这是企微消息气泡的固定结构
     */
    private fun findMessageContent(node: AccessibilityNodeInfo): String? {
        val frameLayouts = NodeFinder.findAllByClassName(node, "android.widget.FrameLayout")
        for (frame in frameLayouts) {
            for (i in 0 until frame.childCount) {
                val child = frame.getChild(i) ?: continue
                if (child.className?.toString() == "android.widget.TextView") {
                    val text = child.text?.toString()
                    if (!text.isNullOrBlank()) return text
                }
            }
        }
        return null
    }

    /**
     * 获取消息内容 TextView 的坐标（用于判断名字位置）
     */
    private fun findMessageContentBounds(node: AccessibilityNodeInfo): Rect? {
        val frameLayouts = NodeFinder.findAllByClassName(node, "android.widget.FrameLayout")
        for (frame in frameLayouts) {
            for (i in 0 until frame.childCount) {
                val child = frame.getChild(i) ?: continue
                if (child.className?.toString() == "android.widget.TextView") {
                    val text = child.text?.toString()
                    if (!text.isNullOrBlank()) {
                        val rect = Rect()
                        child.getBoundsInScreen(rect)
                        return rect
                    }
                }
            }
        }
        return null
    }

    /**
     * 检查节点下是否有大尺寸 ImageView（图片消息）
     */
    private fun findLargeImage(node: AccessibilityNodeInfo, minSize: Int): Boolean {
        val images = NodeFinder.findAllByClassName(node, "android.widget.ImageView")
        for (img in images) {
            val r = Rect()
            img.getBoundsInScreen(r)
            if (r.width() > minSize && r.height() > minSize) return true
        }
        return false
    }

    // ===== 策略2：文本节点兜底（限定范围）=====

    private fun collectByTextNodes(root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): List<Storage.Message> {
        val messages = mutableListOf<Storage.Message>()
        val allTextViews = NodeFinder.findAllByClassName(root, "android.widget.TextView")
        if (allTextViews.isEmpty()) {
            log("[采集] 屏幕上没有 TextView")
            return messages
        }

        val yMin = (screenHeight * 0.15).toInt()
        val yMax = (screenHeight * 0.88).toInt()
        val halfWidth = screenWidth / 2

        // 收集聊天区域内的文本节点，排除时间/系统消息/UI元素
        val textItems = allTextViews.mapNotNull { tv ->
            val text = tv.text?.toString()?.trim() ?: return@mapNotNull null
            if (text.isEmpty()) return@mapNotNull null
            val rect = Rect()
            tv.getBoundsInScreen(rect)
            if (rect.centerY() < yMin || rect.centerY() > yMax) return@mapNotNull null
            if (rect.width() < 10 || rect.height() < 10) return@mapNotNull null
            if (isTimeLabel(text)) return@mapNotNull null
            if (isSystemMessage(text)) return@mapNotNull null
            if (isUiElement(text)) return@mapNotNull null
            NodeFinder.TextNode(text, rect)
        }.sortedBy { it.bounds.centerY() }

        val groups = groupByProximity(textItems, 60)

        // sender 判断：综合 left 和 right 边界
        // 我的消息：右对齐，right 接近屏幕右侧（> 85%屏宽）
        // 别人的消息：左对齐，right 通常不超过屏幕右侧
        // 注意：宽气泡（如长 URL）left 可能很小但 right 接近右侧 → 仍是"我"
        val rightThreshold = (screenWidth * 0.85).toInt()

        for (group in groups) {
            val maxRight = group.maxOf { it.bounds.right }
            val isRightSide = maxRight >= rightThreshold
            val content = group.joinToString(" ") { it.text.trimEnd() }
            if (content.isBlank()) continue

            messages.add(Storage.Message(
                sender = if (isRightSide) "我" else "群成员",
                time = "",
                content = content,
                type = if (content.contains("http")) "link" else "text"
            ))
        }
        return messages
    }

    // ===== 工具方法 =====

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
        // 注意：关键词必须足够具体，避免误匹配用户消息
        // 从真实 dump 中确认的系统消息格式：
        // - "你邀请拒绝、Yummy加入了群聊"
        // - "你将Yummy移出了群聊"
        // - "你修改群名为\"采集群\""
        val keywords = listOf(
            "加入了群聊", "退出了群聊", "移出了群聊",
            "修改群名为", "修改群名",
            "撤回了一条消息", "你已被",
            "你邀请", "邀请你加入",
            "创建了群聊", "解散了群聊",
            "开启了群公告", "清除了群公告",
            "成为新群主", "设置为管理员", "移除了管理员"
        )
        return keywords.any { text.contains(it) }
    }

    private fun isUiElement(text: String): Boolean {
        val uiTexts = setOf("消息", "通讯录", "工作台", "我", "发送", "更多", "返回",
            "表情", "语音", "文件", "拍摄", "位置", "视频通话", "语音通话")
        if (text in uiTexts) return true
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

    /**
     * 检查当前是否还在聊天页面（ListView 存在）
     * 用于滑动后安全检查，防止滑出聊天页面
     */
    fun isChatPageVisible(service: WeWorkAccessibilityService): Boolean {
        val root = service.getRootNode() ?: return false
        // 企微聊天页面特征：有 ListView
        return NodeFinder.findByClassName(root, "android.widget.ListView") != null
    }

    // ===== 数据类 =====

    data class BookmarkResult(val index: Int, val message: Storage.Message, val totalOnScreen: Int)

    private sealed class ParseResult {
        data class TimeLabel(val time: String) : ParseResult()
        data class Msg(val message: Storage.Message) : ParseResult()
        object Skip : ParseResult()
    }
}
