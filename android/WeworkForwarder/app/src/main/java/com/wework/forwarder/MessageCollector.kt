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
                // Leader-Follower 严格验证(防重复内容假阳性):
                // - prevContent 为空(老书签或首次保存) → 跳过验证,单条匹配
                // - prevContent 非空 + i==0 → prev 不在屏幕,无法验证 → 视为不命中,继续往上找
                // - prevContent 非空 + i>0 → prev 必须严格匹配
                // - prevPrevContent 非空 → 第三层验证(三重锁,防三连复读假阳性)
                if (bookmark.prevContent.isNotEmpty()) {
                    if (i == 0) continue
                    val prev = messages[i - 1]
                    if (prev.sender != bookmark.prevSender || prev.content.take(30) != bookmark.prevContent) {
                        continue
                    }
                    if (bookmark.prevPrevContent.isNotEmpty()) {
                        if (i == 1) continue
                        val prevPrev = messages[i - 2]
                        if (prevPrev.sender != bookmark.prevPrevSender || prevPrev.content.take(30) != bookmark.prevPrevContent) {
                            continue
                        }
                    }
                }
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
                // 严格 Leader-Follower 验证(三重锁):
                // i==0/i==1 时 prev/prevPrev 不在屏幕,跳过让 scrollUpToBookmark 再向上滑
                if (bookmark.prevContent.isNotEmpty()) {
                    if (i == 0) continue
                    val prev = messages[i - 1]
                    if (prev.sender != bookmark.prevSender || prev.content.take(30) != bookmark.prevContent) {
                        continue
                    }
                    if (bookmark.prevPrevContent.isNotEmpty()) {
                        if (i == 1) continue
                        val prevPrev = messages[i - 2]
                        if (prevPrev.sender != bookmark.prevPrevSender || prevPrev.content.take(30) != bookmark.prevPrevContent) {
                            continue
                        }
                    }
                }
                return BookmarkResult(index = i, message = messages[i], totalOnScreen = messages.size)
            }
        }
        return null
    }

    fun scrollUpToBookmark(service: WeWorkAccessibilityService, metrics: DisplayMetrics, maxScrolls: Int = 30): BookmarkResult? {
        var lastFirstContent = ""
        var sameCount = 0

        for (i in 0 until maxScrolls) {
            if (!CollectorService.isRunning) return null
            val result = findBookmarkOnScreen(service)
            if (result != null) return result

            // 到顶检测：连续 6 次第一条消息内容不变 → 已到顶，不再无意义滑动
            val messages = collectVisibleMessages(service)
            val firstContent = messages.firstOrNull()?.content ?: ""
            if (firstContent == lastFirstContent && firstContent.isNotEmpty()) {
                sameCount++
                if (sameCount >= 6) {
                    log("[采集] 已到达消息列表顶部，停止查找书签")
                    return null
                }
            } else {
                sameCount = 0
                lastFirstContent = firstContent
            }

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
                // 严格 Leader-Follower 验证(三重锁):
                // i==0/i==1 时 prev/prevPrev 不在屏幕,跳过此候选
                if (bookmark.prevContent.isNotEmpty()) {
                    if (i == 0) continue
                    val prev = messages[i - 1]
                    if (prev.sender != bookmark.prevSender || prev.content.take(30) != bookmark.prevContent) {
                        continue
                    }
                    if (bookmark.prevPrevContent.isNotEmpty()) {
                        if (i == 1) continue
                        val prevPrev = messages[i - 2]
                        if (prevPrev.sender != bookmark.prevPrevSender || prevPrev.content.take(30) != bookmark.prevPrevContent) {
                            continue
                        }
                    }
                }
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

        // 终极兜底：取列表顶部可点击列表项（兼容小程序/图片等无文本消息类型）
        val chatList = NodeFinder.findByClassName(root, "android.widget.ListView")
            ?: NodeFinder.findByClassName(root, "androidx.recyclerview.widget.RecyclerView")
        if (chatList != null) {
            val screenHeight = service.resources.displayMetrics.heightPixels
            val yMin = (screenHeight * 0.15).toInt()
            val yMax = (screenHeight * 0.90).toInt()
            for (i in 0 until chatList.childCount) {
                val child = chatList.getChild(i) ?: continue
                val rect = Rect()
                child.getBoundsInScreen(rect)
                if (rect.centerY() < yMin || rect.centerY() > yMax) continue
                if (rect.height() < 30) continue
                if (child.isClickable) {
                    log("[采集] 兜底: 取顶部可点击列表项定位到控件")
                    return child
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
     * 从真实 dump (v1.5.6 auto_dump_chat.txt) 分析的两种消息结构：
     *
     * 【我的消息】
     *   RelativeLayout (clickable)
     *     └ RelativeLayout
     *       ├ RelativeLayout > TextView text=" "  ← 头像占位符（右侧，left~660+）
     *       └ LinearLayout > ... > FrameLayout > TextView text="内容 "
     *       └ ImageView (已读标记，右侧 left=945)
     *
     * 【别人的消息】
     *   RelativeLayout (clickable)
     *     ├ [可选] LinearLayout (时间标签)
     *     └ RelativeLayout
     *       ├ ImageView bounds=[28,y,135,y2]  ← 真实头像（左侧，尺寸~107x107）
     *       └ RelativeLayout
     *         ├ ViewGroup > TextView text="发送人名"  ← 名字
     *         └ RelativeLayout > ... > FrameLayout > TextView text="内容 "
     *
     * 【系统消息/时间标签】无头像，无左侧ImageView，文本居中
     */
    private fun parseListItem(node: AccessibilityNodeInfo, halfWidth: Int, currentTime: String): ParseResult {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // 高度太小 → 分隔线/空占位
        if (rect.height() < 30) return ParseResult.Skip

        // 收集所有文本节点（含坐标）
        val allTexts = NodeFinder.getAllTexts(node)

        // 我的消息的头像占位符：text=" "，尺寸合理，位于右侧
        val avatarPlaceholders = allTexts.filter {
            it.text.trim().isEmpty()
                    && it.bounds.width() in 20..150
                    && it.bounds.height() in 20..150
        }
        val contentTexts = allTexts.filter { it.text.trim().isNotEmpty() }

        // 别人消息的头像：左侧的大 ImageView（bounds left < 200, 尺寸 80-150px）
        val leftAvatarImage = findLeftAvatarImage(node)

        // 判断消息类型
        val hasMyAvatar = avatarPlaceholders.any { it.bounds.centerX() >= halfWidth }
        val hasOtherAvatar = leftAvatarImage != null
        val hasAvatar = hasMyAvatar || hasOtherAvatar

        // 从 FrameLayout > TextView 提取消息内容
        val msgContent = findMessageContent(node)

        if (!hasAvatar) {
            // 无头像 → 时间标签 和/或 系统消息
            var foundTime: String? = null
            var isSystem = false
            for (tv in contentTexts) {
                if (isTimeLabel(tv.text)) foundTime = tv.text
                else if (isSystemMessage(tv.text)) isSystem = true
            }
            if (foundTime != null) return ParseResult.TimeLabel(foundTime)
            if (isSystem) return ParseResult.Skip

            // 可能是没有头像的图片消息（自己发的图片，头像占位符在左侧）
            if (avatarPlaceholders.isNotEmpty() && contentTexts.isEmpty()) {
                val hasLargeImage = findLargeImage(node, 150)
                if (hasLargeImage) {
                    val avatarX = avatarPlaceholders.first().bounds.centerX()
                    val sender = if (avatarX < halfWidth) "群成员" else "我"
                    return ParseResult.Msg(Storage.Message(sender = sender, time = currentTime, content = "[图片]", type = "image"))
                }
            }

            // 既不是时间也不是已知系统消息 → 跳过
            return ParseResult.Skip
        }

        // === 有头像 → 普通消息 ===
        val isMe = hasMyAvatar && !hasOtherAvatar

        if (msgContent != null) {
            val content = msgContent.trimEnd()
            var sender = if (isMe) "我" else "群成员"

            // 别人的消息：从 ViewGroup > TextView 提取发送人名字
            if (!isMe) {
                sender = findSenderName(node, content) ?: "群成员"
            }

            val type = when {
                content.contains("http") -> "link"
                else -> "text"
            }
            return ParseResult.Msg(Storage.Message(sender = sender, time = currentTime, content = content, type = type))
        }

        // === 卡片识别(小程序/文件/链接/公众号等) ===
        // 企微卡片底部有固定标识 TextView(如"小程序"),用它做主信号
        val cardLabel = findCardLabel(node)
        if (cardLabel != null) {
            val sender = if (isMe) "我" else (findSenderName(node, "") ?: "群成员")
            val title = findCardTitle(node, sender, cardLabel) ?: ""
            val content = if (title.isNotEmpty()) "[$cardLabel] $title" else "[$cardLabel]"
            return ParseResult.Msg(Storage.Message(sender = sender, time = currentTime, content = content, type = "card"))
        }

        // 有头像但没有文本内容 → 图片/文件消息
        val hasLargeImage = findLargeImage(node, 150)
        if (hasLargeImage) {
            val sender = if (isMe) "我" else "群成员"
            return ParseResult.Msg(Storage.Message(sender = sender, time = currentTime, content = "[图片]", type = "image"))
        }

        return ParseResult.Skip
    }

    /**
     * 查找别人消息的头像 ImageView（左侧，尺寸 80-150px）
     * 别人的头像特征：bounds.left < 200, 宽高在 80-150 范围
     */
    private fun findLeftAvatarImage(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val images = NodeFinder.findAllByClassName(node, "android.widget.ImageView")
        for (img in images) {
            val r = Rect()
            img.getBoundsInScreen(r)
            // 左侧头像：left < 200, 尺寸约 107x107
            if (r.left < 200 && r.width() in 70..180 && r.height() in 70..180) {
                return img
            }
        }
        return null
    }

    /**
     * 从 ViewGroup > TextView 提取发送人名字
     * 别人的消息结构中，名字在 ViewGroup 下的 TextView 里
     */
    private fun findSenderName(node: AccessibilityNodeInfo, msgContent: String): String? {
        val viewGroups = NodeFinder.findAllByClassName(node, "android.view.ViewGroup")
        for (vg in viewGroups) {
            for (i in 0 until vg.childCount) {
                val child = vg.getChild(i) ?: continue
                if (child.className?.toString() == "android.widget.TextView") {
                    val text = child.text?.toString()?.trim()
                    if (!text.isNullOrEmpty()
                        && text != msgContent
                        && text != "$msgContent "
                        && !isTimeLabel(text)
                        && !isSystemMessage(text)) {
                        return text
                    }
                }
            }
        }
        return null
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
     * 卡片标识集合 — 企微卡片底部固定有这类短文本 TextView
     */
    private val CARD_LABELS = setOf("小程序", "文件", "链接", "公众号", "音频", "视频", "位置", "名片", "网页")

    /**
     * 在节点子树中找卡片底部的"标识"文本(如"小程序")
     * 返回标识字符串(如"小程序"),未匹配返回 null
     */
    private fun findCardLabel(node: AccessibilityNodeInfo): String? {
        val texts = NodeFinder.getAllTexts(node)
        for (t in texts) {
            val s = t.text.trim()
            if (s.length <= 6 && s in CARD_LABELS) return s
        }
        return null
    }

    /**
     * 提取卡片标题(主标题 TextView 文本)
     * 排除发送人名/卡片标识本身/时间标签/系统消息/过短/过长文本
     */
    private fun findCardTitle(node: AccessibilityNodeInfo, sender: String, label: String): String? {
        val texts = NodeFinder.getAllTexts(node)
        return texts.firstOrNull { t ->
            val s = t.text.trim()
            s.isNotEmpty()
                    && s != sender
                    && s != label
                    && !isTimeLabel(s)
                    && !isSystemMessage(s)
                    && s.length in 1..40
        }?.text?.trim()
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
        val yMax = (screenHeight * 0.95).toInt()
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
