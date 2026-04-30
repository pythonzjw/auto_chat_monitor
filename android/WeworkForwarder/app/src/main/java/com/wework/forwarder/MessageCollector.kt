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

    fun getLastMessage(service: WeWorkAccessibilityService): Storage.Message? {
        return collectVisibleMessages(service).lastOrNull()
    }

    /**
     * v2.0: 取屏幕底部往上数第 K 条消息(及其 ListView 节点,供长按)
     *
     * 调用方已 scrollToBottom,屏幕末端就是最新消息。
     * 如果屏幕可见数 < K,先 swipeUp 加载更多历史消息再重试(最多 5 次)。
     *
     * @param k 1 = 最后一条, 2 = 倒数第二条, ...
     * @return null 表示控件树异常或屏幕无消息
     *         屏幕仍 < K 时降级为屏幕第一条(已经到顶,这就是能拿到的最早消息)
     */
    fun getNthFromBottomMessage(
        service: WeWorkAccessibilityService,
        metrics: DisplayMetrics,
        k: Int,
        expectedMinCount: Int = k
    ): FirstNewMessageInfo? {
        if (k < 1) return null

        // v2.1.0: 数据源切到 OCR (截屏 + MLKit 中文识别)
        // 旧路径(parseListItem)依赖头像锚点,头像缺失/截断时漏消息;
        // OCR 直接读屏幕像素,只关心文字。
        var screenMessages = OcrCollector.collectFromScreen(service)
        if (screenMessages == null) {
            log("[采集] OCR 不可用 (Android < 11 或截屏失败),无法取倒数第 $k 条")
            return null
        }
        if (screenMessages.isEmpty()) {
            log("[采集] OCR 屏幕无消息,无法取倒数第 $k 条")
            return null
        }

        // 累积策略:
        // - 第一批 addAll(顺序: 屏幕从上到下 = 旧到新)
        // - 后续 swipeUp 加载更老消息 → 新增的整批 unshift 到列表头
        // - 最终 allMessages 按时间从最早到最新排序
        // - 倒数第 K 条 = allMessages[size - K]
        // - 长按目标的 rect 始终来自最后一次 OCR(当前屏幕位置),可直接 dispatchGesture
        //
        // 去重 key: content + rect.top 按 100px 分桶(滚动后 top 变,容错)
        val allMessages = mutableListOf<OcrCollector.OcrMessage>()
        val seenKeys = mutableSetOf<String>()

        fun keyOf(m: OcrCollector.OcrMessage) = "${m.content}|${m.rect.top / 100}"

        for (m in screenMessages) {
            if (seenKeys.add(keyOf(m))) allMessages.add(m)
        }
        log("[采集] 初始 OCR ${screenMessages.size} 条, 累计 ${allMessages.size} 条")

        var swipeUps = 0
        var consecutiveNoNew = 0
        while (allMessages.size < expectedMinCount && swipeUps < 30 && consecutiveNoNew < 3) {
            if (!CollectorService.isRunning) return null
            GestureHelper.swipeUp(service, metrics)
            GestureHelper.delay(300)
            if (!isChatPageVisible(service)) {
                log("[采集] swipeUp 后离开聊天页面,停止")
                return null
            }
            screenMessages = OcrCollector.collectFromScreen(service) ?: emptyList()
            swipeUps++

            // 新屏幕去重后,把新增的整批 unshift 到 allMessages 头(它们是更老的消息)
            val newOnes = mutableListOf<OcrCollector.OcrMessage>()
            for (m in screenMessages) {
                if (seenKeys.add(keyOf(m))) newOnes.add(m)
            }
            if (newOnes.isNotEmpty()) {
                allMessages.addAll(0, newOnes)
                consecutiveNoNew = 0
            } else {
                consecutiveNoNew++
            }

            log("[采集] swipeUp 第 $swipeUps 轮，OCR ${screenMessages.size} 条，新增 ${newOnes.size} 条，累计 ${allMessages.size} 条 (目标 >= $expectedMinCount, 连续无新增 $consecutiveNoNew/3)")
        }

        if (consecutiveNoNew >= 3) {
            log("[采集] 连续 3 轮无新增，已到顶。累计 ${allMessages.size} 条")
        } else if (allMessages.size < expectedMinCount) {
            log("[采集] swipeUp ${swipeUps} 轮后累计 ${allMessages.size} 条 < 目标 $expectedMinCount")
        }

        val actualK = Math.min(k, allMessages.size)
        val targetIdx = allMessages.size - actualK
        val target = allMessages[targetIdx]
        log("[采集] 倒数第 $actualK 条 = 累计第 ${targetIdx + 1}/${allMessages.size}: ${target.sender}: ${target.content.take(30)}")
        return FirstNewMessageInfo(target.toStorageMsg(), node = null, rect = target.rect)
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
        return collectByStructureWithNodes(root, screenWidth).first
    }

    /**
     * 同 collectByStructure,但额外返回每条 message 对应的 ListView child 节点
     *
     * 返回值:Pair(messages, nodes), 二者长度相等且一一对应
     *   - messages[i] 是从 nodes[i] 解析出来的消息
     *   - 用于 getNthFromBottomMessage: 取 nodes[size-k] 直接长按
     *
     * 这样绕开 findByText(content) 的限制——小程序/图片等 content 是合成串
     * (如 "[小程序] xxx") 在 TextView 里根本不存在,无法文本匹配,只能物理位置定位
     */
    private fun collectByStructureWithNodes(
        root: AccessibilityNodeInfo, screenWidth: Int
    ): Pair<List<Storage.Message>, List<AccessibilityNodeInfo>> {
        val messages = mutableListOf<Storage.Message>()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val halfWidth = screenWidth / 2

        val chatList = NodeFinder.findByClassName(root, "android.widget.ListView")
        if (chatList == null) {
            log("[策略1] 找不到 ListView，根包名=${root.packageName}, class=${root.className}")
            return Pair(messages, nodes)
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
                is ParseResult.Msg -> { messages.add(result.message); nodes.add(child) }
                is ParseResult.Skip -> skipCount++
            }
        }
        log("[策略1] 解析结果: ${messages.size}条消息, ${timeCount}个时间, ${skipCount}个跳过")
        return Pair(messages, nodes)
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
     *
     * v1.9.3: 改用面积最大的合格 TextView,而不是 DFS 顺序第一条。
     * 旧 firstOrNull 会被 sender 旁边的 at-mention 抢跑(实例:`＠微信` 长度 3 → 抓成 `[小程序] ＠微信`),
     * 真正的卡片标题永远轮不到。卡片标题字号大、占面积也大,改 maxByOrNull(area) 即可命中。
     * 同时排除 ^[@＠] at-mention 模式 + 长度下限 3,杜绝该误抓。
     */
    private fun findCardTitle(node: AccessibilityNodeInfo, sender: String, label: String): String? {
        val texts = NodeFinder.getAllTexts(node)
        return texts
            .filter { t ->
                val s = t.text.trim()
                s.isNotEmpty()
                        && s != sender
                        && s != label
                        && !isTimeLabel(s)
                        && !isSystemMessage(s)
                        && s.length in 3..40
                        && !Regex("^[@＠]").containsMatchIn(s)
            }
            .maxByOrNull { it.bounds.width() * it.bounds.height() }
            ?.text?.trim()
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
        // v1.9.3: 0.95 → 0.88,屏蔽底部输入栏上方的工具栏(企业名片/发起收款/快捷回复/推荐客服)
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
        val uiTexts = setOf(
            // 主导航
            "消息", "通讯录", "工作台", "我",
            // 输入栏 / 通话
            "发送", "更多", "返回", "表情", "语音", "文件", "拍摄", "位置",
            "视频通话", "语音通话",
            // v1.9.3: 外部群/快团团/客服工具栏(生产中常见,误抓会污染书签)
            "企业名片", "发起收款", "快捷回复", "推荐客服",
            "审批", "日报", "周报", "打卡", "公告",
            "群机器人", "群待办", "聊天信息", "群公告"
        )
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

    data class FirstNewMessageInfo(
        val message: Storage.Message,
        val node: AccessibilityNodeInfo?,
        val rect: Rect? = null,
    )

    private sealed class ParseResult {
        data class TimeLabel(val time: String) : ParseResult()
        data class Msg(val message: Storage.Message) : ParseResult()
        object Skip : ParseResult()
    }
}
