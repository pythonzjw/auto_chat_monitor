package com.wework.forwarder

import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 消息转发模块
 *
 * 完整转发流程：
 *   1. 滚到底部 → 记录最后一条消息
 *   2. 定位第一条新消息
 *   3. 转发前保存书签（宁可漏不可重）
 *   4. 长按 → 多选 → 逐条勾选新消息 → 转发
 *   5. 在选群页面切换多选模式，动态定位 checkbox 坐标逐群勾选（每批最多9个）
 *   6. 发送
 *   7. 如果目标群 > 9 个，回到源群重复 4-6
 */
object MessageForwarder {
    private const val TAG = "Forwarder"

    private fun log(msg: String) {
        Log.i(TAG, msg)
        Config.uiLog?.invoke(msg)
    }

    private fun stopped(): Boolean = !CollectorService.isRunning

    /**
     * 执行完整的转发流程（支持分批）
     * 前提：当前已在源群聊天页面
     */
    fun forwardNewMessages(service: WeWorkAccessibilityService, metrics: DisplayMetrics): Boolean {
        log("[转发] 开始执行转发流程...")

        // 步骤1：滚动到底部
        log("[转发] 步骤1: 滚动到最新消息...")
        scrollToBottom(service, metrics)
        if (stopped()) return false
        GestureHelper.delay(500)

        // 记录最后一条消息（用于步骤3的书签）
        val lastMsg = MessageCollector.getLastMessage(service)
        log("[转发] 最后一条: ${lastMsg?.let { "${it.sender}: ${it.content.take(30)}" } ?: "无"}")

        // 步骤2：定位第一条新消息
        log("[转发] 步骤2: 定位第一条新消息...")
        val bookmark = Storage.getBookmark()
        var firstNewMsg: Storage.Message? = null

        if (bookmark != null) {
            log("[转发] 有书签，查找书签位置...")
            val bookmarkInfo = MessageCollector.scrollUpToBookmark(service, metrics, 30)
            if (stopped()) return false
            if (bookmarkInfo != null) {
                log("[转发] 找到书签(位置${bookmarkInfo.index}/${bookmarkInfo.totalOnScreen})，取第一条新消息...")
                val visibleMsgs = MessageCollector.collectVisibleMessages(service)
                firstNewMsg = if (bookmarkInfo.index + 1 < visibleMsgs.size)
                    visibleMsgs[bookmarkInfo.index + 1] else null
                if (firstNewMsg == null) {
                    GestureHelper.swipeDown(service, metrics)
                    val retryResult = MessageCollector.findBookmarkOnScreen(service)
                    val retryMsgs = MessageCollector.collectVisibleMessages(service)
                    firstNewMsg = if (retryResult != null && retryResult.index + 1 < retryMsgs.size)
                        retryMsgs[retryResult.index + 1] else retryMsgs.firstOrNull()
                }
            } else {
                log("[转发] 书签未找到，从当前可见最早消息开始")
                val visibleMsgs = MessageCollector.collectVisibleMessages(service)
                firstNewMsg = visibleMsgs.firstOrNull()
            }
        } else {
            log("[转发] 首次运行，回溯 ${Config.lookbackMinutes} 分钟...")
            scrollUpForMinutes(service, metrics, Config.lookbackMinutes)
            if (stopped()) return false
            val visibleMsgs = MessageCollector.collectVisibleMessages(service)
            firstNewMsg = visibleMsgs.firstOrNull()
        }

        if (firstNewMsg == null) {
            log("[转发] ✗ 没有找到新消息，跳过")
            dumpOnFailure(service, "找不到新消息")
            return false
        }
        log("[转发] 第一条新消息: ${firstNewMsg.sender}: ${firstNewMsg.content.take(30)}")

        // ---- 指纹检查：跳过已转发的消息 ----
        val fp = Storage.fingerprint(firstNewMsg.sender, firstNewMsg.time, firstNewMsg.content)
        if (Storage.isForwarded(fp)) {
            log("[转发] 指纹命中，该消息已转发，跳过: ${firstNewMsg.content.take(30)}")
            return true
        }

        // 步骤3：转发前保存书签（同时保存前一条消息+消息总数用于后续检测）
        // 注意：书签在转发前保存（宁可漏不可重），崩溃重启只漏这批，不重发
        if (lastMsg != null) {
            val visibleMsgs = MessageCollector.collectVisibleMessages(service)
            val prevMsg = if (visibleMsgs.size >= 2) visibleMsgs[visibleMsgs.size - 2] else null
            Storage.saveBookmark(
                lastMsg.sender, lastMsg.content, lastMsg.time,
                prevMsg?.sender ?: "", prevMsg?.content ?: "",
                visibleMsgs.size
            )
            log("[转发] 步骤3: 书签已更新 → ${lastMsg.content.take(20)}")
        }

        // 分批转发
        val batches = Config.targetGroups.chunked(Config.BATCH_SIZE)
        log("[转发] ${Config.targetGroups.size} 个目标群，分 ${batches.size} 批")

        for ((batchIdx, batch) in batches.withIndex()) {
            if (stopped()) return false
            log("[转发] === 第 ${batchIdx + 1}/${batches.size} 批（${batch.size}个群）===")

            // 第2批起需要回源群
            if (batchIdx > 0) {
                log("[转发] 回到源群...")
                Navigator.enterGroup(service, metrics, Config.sourceGroup)
                if (stopped()) return false
                GestureHelper.delayExact(Config.enterGroupWaitSeconds * 1000L)
                scrollToBottom(service, metrics)
                if (stopped()) return false
                GestureHelper.delay(500)
            }

            // 步骤4：长按第一条新消息
            log("[转发] 步骤4: 长按消息...")
            val msgElem = MessageCollector.findMessageElement(service, firstNewMsg)
            if (msgElem == null) {
                log("[转发] ✗ 找不到消息控件，无法长按")
                dumpOnFailure(service, "找不到消息控件_批${batchIdx + 1}")
                if (batchIdx == 0) return false else continue
            }
            val rect = Rect()
            msgElem.getBoundsInScreen(rect)
            log("[转发] 长按坐标: (${rect.centerX()}, ${rect.centerY()})")
            service.longPressAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            GestureHelper.delay(1200)

            // 步骤5：点击"多选"（在所有企微窗口中查找，包括弹出菜单）
            log("[转发] 步骤5: 查找'多选'...")
            val multiSelectBtn = waitForNodeInAllWindows(service, 3000) { root ->
                NodeFinder.findByText(root, "多选") ?: NodeFinder.findByDesc(root, "多选")
            }
            if (multiSelectBtn == null) {
                log("[转发] ✗ 找不到'多选'按钮")
                dumpAllWindows(service, "找不到多选_批${batchIdx + 1}")
                dismissPopup(service, metrics)
                if (batchIdx == 0) return false else continue
            }
            NodeFinder.clickNode(service, multiSelectBtn)
            log("[转发] ✓ 已点击'多选'")
            GestureHelper.delay(800)

            // 步骤6：勾选新消息
            log("[转发] 步骤6: 勾选新消息...")
            val oldBookmark = bookmark
            if (!scrollAndSelectToHere(service, metrics, rect.centerY(), oldBookmark)) {
                log("[转发] ✗ 勾选失败")
                dumpOnFailure(service, "勾选失败_批${batchIdx + 1}")
                exitMultiSelect(service)
                if (batchIdx == 0) return false else continue
            }
            log("[转发] ✓ 新消息已勾选")

            // 步骤7：点击转发
            log("[转发] 步骤7: 点击'转发'...")
            if (!clickForward(service)) {
                log("[转发] ✗ 点击转发失败")
                dumpOnFailure(service, "转发按钮失败_批${batchIdx + 1}")
                exitMultiSelect(service)
                if (batchIdx == 0) return false else continue
            }
            log("[转发] ✓ 已进入选群页面")

            // 步骤8：选择目标群
            log("[转发] 步骤8: 选群 (${batch.joinToString(",")})...")
            if (!selectTargetGroups(service, batch)) {
                log("[转发] ✗ 选群失败")
                dumpOnFailure(service, "选群失败_批${batchIdx + 1}")
                service.pressBack()
                exitMultiSelect(service)
                if (batchIdx == 0) return false else continue
            }

            // 步骤9：发送
            log("[转发] 步骤9: 发送...")
            if (!confirmSend(service)) {
                log("[转发] ✗ 发送失败")
                dumpOnFailure(service, "发送失败_批${batchIdx + 1}")
                if (batchIdx == 0) return false else continue
            }
            log("[转发] ✓ 第 ${batchIdx + 1} 批发送成功")

            // 批间延时
            if (batchIdx < batches.size - 1) {
                val d = 2000L + (Math.random() * 3000).toLong()
                log("[转发] 等待 ${d / 1000} 秒...")
                GestureHelper.delayExact(d)
            }
        }

        // 转发成功后记录指纹，防止重复转发
        Storage.markForwarded(fp)
        Storage.saveMessages(listOf(firstNewMsg))
        log("[转发] ✓ 全部完成！")
        return true
    }

    // ===== 内部方法 =====

    private fun scrollToBottom(service: WeWorkAccessibilityService, metrics: DisplayMetrics) {
        repeat(5) {
            if (stopped()) return
            GestureHelper.swipeDown(service, metrics)
        }
    }

    private fun scrollUpForMinutes(service: WeWorkAccessibilityService, metrics: DisplayMetrics, minutes: Int) {
        var lastFirstContent = ""
        var sameCount = 0
        for (i in 0 until 50) {
            if (stopped()) return
            val messages = MessageCollector.collectVisibleMessages(service)

            // 检查是否到达回溯时间边界
            for (msg in messages) {
                val msgTime = TimeParser.parseMessageTime(msg.time)
                if (msgTime != null && !TimeParser.isWithinLookback(msgTime, minutes)) {
                    log("[转发] 已到达回溯时间边界")
                    return
                }
            }

            // 检查是否到顶（连续 3 次内容不变 → 不再滑动）
            val firstContent = messages.firstOrNull()?.content ?: ""
            if (firstContent == lastFirstContent && firstContent.isNotEmpty()) {
                sameCount++
                if (sameCount >= 3) {
                    log("[转发] 已到达消息列表顶部，停止回溯")
                    return
                }
            } else {
                sameCount = 0
                lastFirstContent = firstContent
            }

            GestureHelper.swipeUp(service, metrics)
            // 安全检查：滑动后确认还在聊天页面
            if (!MessageCollector.isChatPageVisible(service)) {
                log("[转发] 滑动后已离开聊天页面，停止回溯")
                return
            }
        }
    }

    /**
     * 勾选所有新消息：从底部向上逐个点 checkbox，遇到旧书签停止。
     *
     * 修复：
     * 1. checkbox 坐标改为动态从控件树中查找，不再硬编码 x=83
     * 2. 书签匹配加入 Leader-Follower 双重验证，防止重复内容假阳性
     */
    private fun scrollAndSelectToHere(
        service: WeWorkAccessibilityService,
        metrics: DisplayMetrics,
        firstMsgY: Int,
        oldBookmark: Storage.Bookmark?
    ): Boolean {
        val screenHeight = metrics.heightPixels
        val yMin = (screenHeight * 0.15).toInt()
        val yMax = (screenHeight * 0.92).toInt()
        var selectedCount = 0
        val nonTextMarkers = listOf("[图片]", "[文件]", "[小程序]", "[视频]", "[链接]", "[位置]", "[名片]")

        // 动态探测 checkbox 的 X 坐标（多选模式下每行左侧会出现 checkbox ImageView）
        val checkboxX = detectCheckboxX(service, metrics) ?: run {
            log("[转发] ✗ 无法探测 checkbox 坐标，使用默认值 83")
            83f
        }
        log("[转发] checkbox X 坐标: $checkboxX")

        for (round in 0 until 10) {
            if (stopped()) break

            val root = service.getRootNode() ?: continue
            val chatList = NodeFinder.findByClassName(root, "android.widget.ListView")
                ?: NodeFinder.findByClassName(root, "androidx.recyclerview.widget.RecyclerView")
            if (chatList == null) continue

            val seenY = mutableSetOf<Int>()
            var hitBookmark = false

            for (i in chatList.childCount - 1 downTo 0) {
                if (stopped()) break
                val child = chatList.getChild(i) ?: continue
                val rect = Rect()
                child.getBoundsInScreen(rect)

                if (rect.centerY() < yMin || rect.centerY() > yMax) continue
                if (rect.height() < 40) continue

                val cy = rect.centerY()
                val yKey = cy / 30 * 30
                if (yKey in seenY) continue
                seenY.add(yKey)

                // 跳过 firstNewMsg 自身（长按已选中，再点会取消）
                if (Math.abs(cy - firstMsgY) < 30) continue

                // 首次运行无书签时，不勾选 firstNewMsg 之上的消息
                if (oldBookmark == null && cy < firstMsgY) continue

                // 检查是否遇到旧书签 → 停止（含 Leader-Follower 双重验证）
                if (oldBookmark != null) {
                    val allTexts = NodeFinder.getAllTexts(child)
                    val childText = allTexts.joinToString(" ") { it.text.trimEnd() }
                    var isBookmark = false

                    if (childText.isNotBlank()) {
                        if (childText.contains(oldBookmark.content.take(20))) {
                            isBookmark = true
                        }
                    } else if (oldBookmark.content in nonTextMarkers) {
                        // 纯图片类书签没有文本，用 isClickable+高度 作为信号
                        isBookmark = child.isClickable && rect.height() > 80
                    }

                    if (isBookmark) {
                        // ---- Leader-Follower 验证（防止重复内容假阳性）----
                        if (oldBookmark.prevContent.isNotEmpty() && i > 0) {
                            val prevChild = chatList.getChild(i - 1)
                            val prevTexts = NodeFinder.getAllTexts(prevChild ?: continue)
                            val prevText = prevTexts.joinToString(" ") { it.text.trimEnd() }
                            val prevMatch = when {
                                prevText.isNotBlank() && prevText.contains(oldBookmark.prevContent.take(20)) -> true
                                oldBookmark.prevContent in nonTextMarkers && prevText.isBlank() -> true
                                else -> false
                            }
                            if (prevMatch) {
                                hitBookmark = true
                                log("[转发] 遇到旧书签（Leader-Follower验证通过），停止勾选。已选 $selectedCount 条")
                                break
                            }
                            // prevContent 不匹配 → 假阳性（重复内容），继续往上找
                            log("[转发] 内容匹配但 Leader-Follower 未通过，跳过（可能是重复内容）")
                            continue
                        }
                        // 没有 prevContent 时退化为单条匹配
                        hitBookmark = true
                        log("[转发] 遇到旧书签（无 prevContent 单条匹配），停止勾选。已选 $selectedCount 条")
                        break
                    }
                }

                service.clickAt(checkboxX, cy.toFloat())
                selectedCount++
                GestureHelper.delay(200)
            }

            if (hitBookmark) break

            // 往下滑加载更多
            GestureHelper.swipeDown(service, metrics)
            GestureHelper.delay(800)
        }

        log("[转发] 勾选完成，共额外勾选 $selectedCount 条")
        return true
    }

    /**
     * 动态探测多选模式下 checkbox 的 X 坐标。
     *
     * 多选模式进入后，列表最左侧会出现一列 ImageView（checkbox），
     * 找到其 centerX 即为点击坐标，不再依赖硬编码的 83px。
     */
    private fun detectCheckboxX(service: WeWorkAccessibilityService, metrics: DisplayMetrics): Float? {
        val screenWidth = metrics.widthPixels
        val root = service.getRootNode() ?: return null
        val chatList = NodeFinder.findByClassName(root, "android.widget.ListView")
            ?: NodeFinder.findByClassName(root, "androidx.recyclerview.widget.RecyclerView")
            ?: return null

        for (i in 0 until minOf(chatList.childCount, 5)) {
            val child = chatList.getChild(i) ?: continue
            val childRect = Rect()
            child.getBoundsInScreen(childRect)
            if (childRect.height() < 40) continue

            // 在每个列表项左侧区域寻找小 ImageView（checkbox 特征：宽高 20-80px，left < 200）
            val images = NodeFinder.findAllByClassName(child, "android.widget.ImageView")
            for (img in images) {
                val r = Rect()
                img.getBoundsInScreen(r)
                if (r.left < (screenWidth * 0.2).toInt()
                    && r.width() in 20..80
                    && r.height() in 20..80
                ) {
                    return r.centerX().toFloat()
                }
            }
        }
        return null
    }

    private fun clickForward(service: WeWorkAccessibilityService): Boolean {
        val root = service.getRootNode()
        val forwardBtn = NodeFinder.findByText(root, "转发")
            ?: NodeFinder.findByDesc(root, "转发")
        if (forwardBtn == null) {
            log("[转发] 找不到'转发'按钮")
            return false
        }
        NodeFinder.clickNode(service, forwardBtn)
        GestureHelper.delay(1000)

        // "逐条转发/合并转发"弹窗是独立窗口，用 getAllRootNodes 查找
        val oneByOne = waitForNodeInAllWindows(service, 3000) { r ->
            NodeFinder.findByText(r, "逐条转发")
        }
        if (oneByOne != null) {
            val rect = Rect()
            oneByOne.getBoundsInScreen(rect)
            log("[转发] 选择'逐条转发' (${rect.centerX()}, ${rect.centerY()})")
            service.clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            GestureHelper.delay(1500)
        }
        return true
    }

    /**
     * 在"选择联系人"页面中勾选目标群（多选模式）
     *
     * 修复：checkbox 坐标从控件树动态查找，不再硬编码 x=83。
     *
     * 流程：
     *   1. 等待页面加载
     *   2. 点右上角对勾 → 切换多选模式
     *   3. 动态探测 checkbox X 坐标
     *   4. 逐个查找群名 → 点击对应行的 checkbox 勾选
     *   5. 点右下角蓝色"确定(N)"按钮
     */
    private fun selectTargetGroups(service: WeWorkAccessibilityService, groups: List<String>): Boolean {
        // 1. 等待选群页面加载
        val pageReady = NodeFinder.waitForNode(service, 3000) { root ->
            NodeFinder.findByText(root, "最近聊天")
                ?: NodeFinder.findByText(root, "选择联系人")
        }
        if (pageReady == null) {
            log("[选群] 选群页面未加载")
            dumpOnFailure(service, "选群页面未加载")
            return false
        }

        // 2. 点右上角对勾切换到多选模式
        if (!switchToMultiSelect(service)) {
            log("[选群] ✗ 切换多选模式失败")
            dumpOnFailure(service, "切换多选失败")
            return false
        }
        log("[选群] ✓ 已切换到多选模式")
        GestureHelper.delay(800)

        // 3. 动态探测 checkbox X 坐标
        val checkboxX = detectContactCheckboxX(service) ?: run {
            log("[选群] ✗ 无法探测选群页面 checkbox 坐标，使用默认值 83")
            83f
        }
        log("[选群] checkbox X 坐标: $checkboxX")

        // 4. 逐个查找群名并勾选
        var selectedCount = 0
        val metrics = service.resources.displayMetrics

        for ((idx, groupName) in groups.withIndex()) {
            if (stopped()) return selectedCount > 0
            log("[选群] (${idx + 1}/${groups.size}) 查找: $groupName")

            var found = false
            for (scroll in 0..5) {
                val root = service.getRootNode() ?: continue
                var result = NodeFinder.findByText(root, groupName)
                if (result == null) {
                    result = NodeFinder.findByTextContains(root, groupName)
                    if (result != null) log("[选群] 模糊匹配到: ${result.text}")
                }

                if (result != null) {
                    val rect = Rect()
                    result.getBoundsInScreen(rect)
                    // 点击动态探测到的 checkbox X，y 取群名行中心
                    service.clickAt(checkboxX, rect.centerY().toFloat())
                    selectedCount++
                    found = true
                    log("[选群] ✓ 已勾选: $groupName ($checkboxX, ${rect.centerY()})")
                    GestureHelper.delay(500)
                    break
                }

                if (scroll < 5) {
                    GestureHelper.swipeDown(service, metrics)
                    GestureHelper.delay(500)
                }
            }

            if (!found) {
                log("[选群] ✗ 未找到: $groupName")
                val root = service.getRootNode()
                if (root != null) {
                    val allTexts = NodeFinder.getAllTexts(root)
                    val summary = allTexts.filter { it.text.trim().isNotEmpty() }
                        .take(10).joinToString(", ") { "\"${it.text.take(20)}\"" }
                    log("[选群] 页面文本: $summary")
                }
            }
        }

        log("[选群] 共勾选 $selectedCount/${groups.size}")

        // 5. 点击底部"确定(N)"按钮
        if (selectedCount > 0) {
            GestureHelper.delay(500)
            val confirmRoot = service.getRootNode()
            val confirmBtn = NodeFinder.findByTextRegex(confirmRoot, Regex("确定\\s*\\(\\d+\\)"))
                ?: NodeFinder.findByText(confirmRoot, "确定")
            if (confirmBtn != null) {
                val cRect = Rect()
                confirmBtn.getBoundsInScreen(cRect)
                log("[选群] 点击: ${confirmBtn.text} (${cRect.centerX()}, ${cRect.centerY()})")
                service.clickAt(cRect.centerX().toFloat(), cRect.centerY().toFloat())
                GestureHelper.delay(1500)
            } else {
                log("[选群] ✗ 找不到确定按钮")
                return false
            }
        }

        return selectedCount > 0
    }

    /**
     * 在选联系人页面中动态探测 checkbox 的 X 坐标。
     *
     * 多选模式下列表每行最左侧有一个 ImageView（checkbox），
     * 通过扫描前几行确定其 centerX。
     */
    private fun detectContactCheckboxX(service: WeWorkAccessibilityService): Float? {
        val root = service.getRootNode() ?: return null
        val metrics = service.resources.displayMetrics
        val screenWidth = metrics.widthPixels

        // 找列表容器（选联系人页面通常是 ListView 或 RecyclerView）
        val list = NodeFinder.findByClassName(root, "android.widget.ListView")
            ?: NodeFinder.findByClassName(root, "androidx.recyclerview.widget.RecyclerView")
            ?: return null

        for (i in 0 until minOf(list.childCount, 5)) {
            val child = list.getChild(i) ?: continue
            val childRect = Rect()
            child.getBoundsInScreen(childRect)
            if (childRect.height() < 40) continue

            val images = NodeFinder.findAllByClassName(child, "android.widget.ImageView")
            for (img in images) {
                val r = Rect()
                img.getBoundsInScreen(r)
                // checkbox 特征：左侧区域，尺寸适中
                if (r.left < (screenWidth * 0.2).toInt()
                    && r.width() in 20..80
                    && r.height() in 20..80
                ) {
                    return r.centerX().toFloat()
                }
            }
        }
        return null
    }

    /**
     * 切换到多选模式
     *
     * 单选 vs 多选的确定性区别（dump 实证）：
     *   单选模式 tab: "最近聊天" + "创建聊天" + "转到微信"
     *   多选模式 tab: "最近聊天" + "从通讯录选择"
     */
    private fun switchToMultiSelect(service: WeWorkAccessibilityService): Boolean {
        if (!service.isInWeWork()) {
            log("[选群] 企微不在前台，无法切换多选")
            return false
        }

        val root = service.getRootNode() ?: return false

        // 已经在多选模式
        if (NodeFinder.findByText(root, "从通讯录选择") != null) {
            log("[选群] 已在多选模式（找到'从通讯录选择'）")
            return true
        }

        val metrics = service.resources.displayMetrics
        val halfWidth = metrics.widthPixels / 2

        // 查找标题栏右侧按钮（y=[80,260]，右半边，排除大容器）
        val clickables = NodeFinder.findAll(root) { node ->
            if (!node.isClickable) return@findAll false
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top >= 80 && rect.bottom <= 260
                    && rect.left > halfWidth
                    && rect.width() < 200
                    && rect.height() < 200
        }

        if (clickables.isEmpty()) {
            log("[选群] 标题栏右侧没有 clickable 节点")
            return false
        }

        // 取 left 最大的（对勾在最右边）
        val btn = clickables.maxByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.left
        } ?: return false

        val rect = Rect()
        btn.getBoundsInScreen(rect)
        log("[选群] 点击对勾按钮 (${rect.centerX()}, ${rect.centerY()})，共找到 ${clickables.size} 个候选")
        service.clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())

        // 等待多选模式生效（最多 3 秒）
        val switched = NodeFinder.waitForNode(service, 3000) { r ->
            NodeFinder.findByText(r, "从通讯录选择")
        }
        if (switched != null) {
            log("[选群] ✓ 多选模式已激活（找到'从通讯录选择'）")
            return true
        }

        // 备选验证：'创建聊天'消失 + '最近聊天'仍在
        val checkRoot = service.getRootNode()
        if (checkRoot != null
            && NodeFinder.findByText(checkRoot, "创建聊天") == null
            && NodeFinder.findByText(checkRoot, "最近聊天") != null
        ) {
            log("[选群] ✓ 多选模式已激活（'创建聊天'已消失）")
            return true
        }

        log("[选群] 点击对勾后未切换到多选模式")
        return false
    }

    /**
     * 处理发送确认弹窗：点击"发送(N)"按钮
     */
    private fun confirmSend(service: WeWorkAccessibilityService): Boolean {
        GestureHelper.delay(800)

        val root = service.getRootNode()
        val sendBtn = NodeFinder.findByTextRegex(root, Regex("发送\\s*\\(\\d+\\)"))
            ?: NodeFinder.findByText(root, "发送")
            ?: NodeFinder.findByText(root, "确定")
            ?: NodeFinder.findByText(root, "确认")
        if (sendBtn == null) {
            log("[转发] 找不到发送按钮")
            return false
        }
        val rect = Rect()
        sendBtn.getBoundsInScreen(rect)
        log("[转发] 点击: ${sendBtn.text} (${rect.centerX()}, ${rect.centerY()})")
        service.clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        GestureHelper.delayExact(2000)
        return true
    }

    private fun exitMultiSelect(service: WeWorkAccessibilityService) {
        val root = service.getRootNode()
        val cancelBtn = NodeFinder.findByText(root, "取消")
            ?: NodeFinder.findByDesc(root, "取消")
            ?: NodeFinder.findByDesc(root, "关闭")
        if (cancelBtn != null) NodeFinder.clickNode(service, cancelBtn)
        else service.pressBack()
        GestureHelper.delay(500)
    }

    private fun dismissPopup(service: WeWorkAccessibilityService, metrics: DisplayMetrics) {
        service.clickAt(metrics.widthPixels / 2f, metrics.heightPixels * 0.1f)
        GestureHelper.delay(500)
    }

    /**
     * 在所有企微窗口中查找节点（包括弹出菜单/popup）
     */
    private fun waitForNodeInAllWindows(
        service: WeWorkAccessibilityService,
        timeout: Long,
        finder: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            for (root in service.getAllRootNodes()) {
                val node = finder(root)
                if (node != null) return node
            }
            GestureHelper.delayExact(300)
        }
        return null
    }

    /**
     * dump 所有企微窗口（诊断用）
     */
    private fun dumpAllWindows(service: WeWorkAccessibilityService, reason: String) {
        try {
            val roots = service.getAllRootNodes()
            val sb = StringBuilder()
            sb.appendLine("失败原因: $reason")
            sb.appendLine("包名: ${service.currentPackage}")
            sb.appendLine("Activity: ${service.currentActivity}")
            sb.appendLine("时间: ${Storage.now()}")
            sb.appendLine("窗口数: ${roots.size}")
            sb.appendLine("==============================")
            for ((i, root) in roots.withIndex()) {
                val rect = Rect()
                root.getBoundsInScreen(rect)
                sb.appendLine("--- 窗口 ${i + 1} (${rect.width()}x${rect.height()}) ---")
                sb.append(NodeFinder.dumpTree(root))
            }
            val ts = System.currentTimeMillis()
            val safeReason = reason.replace(Regex("[^a-zA-Z0-9_\u4e00-\u9fa5]"), "_")
            val filename = "dump_${safeReason}_$ts.txt"
            val path = Storage.saveDump(filename, sb.toString())
            if (path != null) log("[诊断] 控件树已保存: $filename")
        } catch (e: Exception) {
            log("[诊断] dump 异常: ${e.message}")
        }
    }

    private fun dumpOnFailure(service: WeWorkAccessibilityService, reason: String) {
        try {
            val root = service.getRootNode() ?: run {
                log("[诊断] 无法获取根节点")
                return
            }
            val dump = NodeFinder.dumpTree(root)
            val ts = System.currentTimeMillis()
            val safeReason = reason.replace(Regex("[^a-zA-Z0-9_\u4e00-\u9fa5]"), "_")
            val filename = "dump_${safeReason}_$ts.txt"
            val header = buildString {
                appendLine("失败原因: $reason")
                appendLine("包名: ${service.currentPackage}")
                appendLine("Activity: ${service.currentActivity}")
                appendLine("时间: ${Storage.now()}")
                appendLine("==============================")
            }
            val path = Storage.saveDump(filename, header + dump)
            if (path != null) log("[诊断] 控件树已保存: $filename")
            else log("[诊断] 保存失败")
        } catch (e: Exception) {
            log("[诊断] dump 异常: ${e.message}")
        }
    }
}
