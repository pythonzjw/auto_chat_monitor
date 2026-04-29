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
 *   4. 长按 → 多选 → 滚到底全选 → 转发
 *   5. 在选群页面逐个搜索目标群并勾选（每批最多9个）
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

        // 在底部一次性取 last/prev/prevPrev 三条快照(供三重锁书签)
        // scrollUpToBookmark 后屏幕已离开底部,prev/prevPrev 必须在底部时取才真正相邻
        val visibleAtBottom = MessageCollector.collectVisibleMessages(service)
        val lastMsg = visibleAtBottom.lastOrNull()
        val prevMsg = if (visibleAtBottom.size >= 2) visibleAtBottom[visibleAtBottom.size - 2] else null
        val prevPrevMsg = if (visibleAtBottom.size >= 3) visibleAtBottom[visibleAtBottom.size - 3] else null
        log("[转发] 最后一条: ${lastMsg?.let { "${it.sender}: ${it.content.take(30)}" } ?: "无"}")

        // 步骤2：定位第一条新消息(同时拿到它的 ListView child 节点用于长按)
        log("[转发] 步骤2: 定位第一条新消息...")
        val bookmark = Storage.getBookmark()
        var anchor: MessageCollector.FirstNewMessageInfo?

        if (bookmark != null) {
            log("[转发] 有书签，查找书签位置...")
            val bookmarkInfo = MessageCollector.scrollUpToBookmark(service, metrics, 30)
            if (stopped()) return false
            if (bookmarkInfo != null) {
                log("[转发] 找到书签(位置${bookmarkInfo.index + 1}/${bookmarkInfo.totalOnScreen})，取第一条新消息节点...")
                anchor = MessageCollector.findFirstNewMessageWithNode(service)
                if (anchor == null) {
                    GestureHelper.swipeDown(service, metrics)
                    anchor = MessageCollector.findFirstNewMessageWithNode(service)
                }
            } else {
                log("[转发] ✗ 旧书签未找到,本轮跳过转发,保留旧书签等下次再找")
                return false
            }
        } else {
            log("[转发] 首次运行，回溯 ${Config.lookbackMinutes} 分钟...")
            scrollUpForMinutes(service, metrics, Config.lookbackMinutes)
            if (stopped()) return false
            anchor = MessageCollector.findFirstNewMessageWithNode(service)
        }

        if (anchor == null) {
            log("[转发] ✗ 没有找到新消息，跳过")
            dumpOnFailure(service, "找不到新消息")
            return false
        }
        val firstNewMsg = anchor.message
        log("[转发] 第一条新消息: ${firstNewMsg.sender}: ${firstNewMsg.content.take(30)}")

        // 步骤3：转发前保存书签(三重锁,prev/prevPrev 取自底部快照)
        if (lastMsg != null) {
            Storage.saveBookmark(
                lastMsg.sender, lastMsg.content, lastMsg.time,
                prevMsg?.sender ?: "", prevMsg?.content ?: "",
                prevPrevMsg?.sender ?: "", prevPrevMsg?.content ?: ""
            )
            log("[转发] 步骤3: 书签已更新(含 prev/prevPrev)")
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
            // 第1批: 用 step 2 拿到的 anchor.node(物理位置定位,精确)
            // 第2+批: 书签已更新为 lastMsg, 重新调 findFirstNewMessageWithNode 已无意义
            //         降级用旧 findMessageElement 按内容找(老逻辑兜底,小程序场景仍可能不准)
            log("[转发] 步骤4: 长按消息...")
            val msgElem: AccessibilityNodeInfo? = if (batchIdx == 0) {
                anchor.node
            } else {
                MessageCollector.findMessageElement(service, firstNewMsg)
            }
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

            // 步骤6：全选
            log("[转发] 步骤6: 滚动全选...")
            if (!scrollAndSelectToHere(service, metrics)) {
                log("[转发] ✗ 全选失败")
                dumpOnFailure(service, "全选失败_批${batchIdx + 1}")
                exitMultiSelect(service)
                if (batchIdx == 0) return false else continue
            }
            log("[转发] ✓ 全选完成")

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

        Storage.saveMessages(listOf(firstNewMsg))
        log("[转发] ✓ 全部完成！")
        return true
    }

    // ===== 内部方法 =====

    private fun scrollToBottom(service: WeWorkAccessibilityService, metrics: DisplayMetrics) {
        // 滑动慢化后单次幅度 8%,需要更多轮才能到底
        repeat(8) {
            if (stopped()) return
            GestureHelper.swipeDown(service, metrics)
        }
    }

    private fun scrollUpForMinutes(service: WeWorkAccessibilityService, metrics: DisplayMetrics, minutes: Int) {
        var lastFirstContent = ""
        var sameCount = 0
        for (i in 0 until 10) {
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
     * 向下滑动到底部，找到正确方向的"选择到这里"按钮并点击
     *
     * 企微多选模式有两个"选择到这里"按钮（带方向箭头）：
     *   ↑ 选择到这里 — 向下选（我们需要这个：从第一条新消息选到最底部）
     *   ↓ 选择到这里 — 向上选（不要点这个，会选中旧消息）
     *
     * 到底信号：屏幕最后一条消息内容 + ListView childCount 连续 2 轮均不变。
     * 不再用"按钮 y 位置"判断，因为按钮位置取决于多选锚点，与列表是否到底无关。
     * 按钮被滑出屏幕时（滑过头），swipeUp 1 次恢复。
     */
    private fun scrollAndSelectToHere(service: WeWorkAccessibilityService, metrics: DisplayMetrics): Boolean {
        var lastContent = ""
        var lastChildCount = -1
        var stableCount = 0

        for (i in 0 until 20) {
            if (stopped()) return false

            // 每轮强制滑动一次（杜绝零滑动直接判到底）
            GestureHelper.swipeDown(service, metrics)

            // 读当前状态
            val root = service.getRootNode()
            val chatList = root?.let {
                NodeFinder.findByClassName(it, "android.widget.ListView")
                    ?: NodeFinder.findByClassName(it, "androidx.recyclerview.widget.RecyclerView")
            }
            val curChildCount = chatList?.childCount ?: 0
            val curContent = MessageCollector.collectVisibleMessages(service)
                .lastOrNull()?.content ?: ""

            // 按钮消失保护：滑过头会让按钮被滑出屏幕，需要 swipeUp 恢复
            var btn = findSelectToHereDown(service)
            if (btn == null) {
                log("[转发] 按钮不可见，swipeUp 1次恢复")
                GestureHelper.swipeUp(service, metrics)
                btn = findSelectToHereDown(service)
            }

            // 到底判定：内容 + childCount 都不变才算稳定
            val stable = curContent.isNotEmpty()
                    && curContent == lastContent
                    && curChildCount == lastChildCount
            if (stable) {
                stableCount++
                log("[转发] 列表稳定第 ${stableCount} 轮 (last='${curContent.take(20)}', count=$curChildCount)")
                // 连续 2 轮稳定（首次stable→stableCount=1，第二次→2 触发）
                if (stableCount >= 2 && btn != null) {
                    val r = Rect()
                    btn.getBoundsInScreen(r)
                    log("[转发] 到底了，点击'选择到这里' y=${r.centerY()}")
                    service.clickAt(r.centerX().toFloat(), r.centerY().toFloat())
                    GestureHelper.delay(1000)
                    return true
                }
            } else {
                stableCount = 0
            }
            lastContent = curContent
            lastChildCount = curChildCount
        }

        // 兜底：20 轮后最后一次尝试
        val btn = findSelectToHereDown(service) ?: run {
            // 兜底再 swipeUp 一次找按钮
            GestureHelper.swipeUp(service, metrics)
            findSelectToHereDown(service)
        }
        if (btn != null) {
            val rect = Rect()
            btn.getBoundsInScreen(rect)
            log("[转发] 兜底点击'选择到这里' y=${rect.centerY()}")
            service.clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            GestureHelper.delay(1000)
            return true
        }
        log("[转发] 未找到'选择到这里'，可能只有一条新消息")
        return true
    }

    /**
     * 查找"选择到这里"按钮（↑向下选方向）
     *
     * 企微多选模式有两个方向按钮，区分方式：
     *   - 多个候选 → 取 centerY 最大的（最靠底部 = ↑向下选）
     *   - 只有 1 个 → 检查 y 值：上半屏(↓向上选)跳过，下半屏(↑向下选)使用
     *
     * v1.6.1：修复单个按钮在上半屏时误点（y=318 在标题栏附近 = 向上选方向）
     */
    private fun findSelectToHereDown(service: WeWorkAccessibilityService): AccessibilityNodeInfo? {
        val root = service.getRootNode() ?: return null
        val screenHeight = service.resources.displayMetrics.heightPixels

        // 收集所有包含"选择到这里"的节点
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        // 通过 text 查找
        val byText = NodeFinder.findAll(root) { node ->
            val text = node.text?.toString() ?: ""
            text.contains("选择到这里")
        }
        candidates.addAll(byText)

        // 通过 contentDescription 查找
        val byDesc = NodeFinder.findAll(root) { node ->
            val desc = node.contentDescription?.toString() ?: ""
            desc.contains("选择到这里")
        }
        candidates.addAll(byDesc)

        if (candidates.isEmpty()) return null

        if (candidates.size == 1) {
            val rect = Rect()
            candidates[0].getBoundsInScreen(rect)
            if (rect.centerY() < screenHeight / 2) {
                // 在上半屏 = ↓向上选方向，不要点（会选中旧消息）
                log("[转发] 唯一'选择到这里'在上半屏(y=${rect.centerY()})，跳过继续滑")
                return null
            }
            return candidates[0]
        }

        // 多个候选 → 取 centerY 最大的（最靠底部 = ↑向下选方向）
        log("[转发] 找到 ${candidates.size} 个'选择到这里'，取最底部的")
        return candidates.maxByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.centerY()
        }
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

        // "逐条转发/合并转发"弹窗是独立窗口（CustomerBottomListDialog），
        // getRootNode() 取最大窗口会跳过它，必须用 getAllRootNodes 在所有窗口中查找
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
     * 流程：
     *   1. 等待页面加载
     *   2. 点右上角对勾 → 切换多选模式
     *   3. 逐个查找群名 → 坐标点击勾选
     *   4. 点右下角蓝色"确定"按钮
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

        // 3. 逐个查找群名并勾选
        var selectedCount = 0
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
                    // 多选模式：点击左侧 ImageView 勾选框（x≈83），y 取群名行的中心
                    // dump 实证：ImageView bounds=[45,392,121,482]，centerX=83
                    val rect = Rect()
                    result.getBoundsInScreen(rect)
                    service.clickAt(83f, rect.centerY().toFloat())
                    selectedCount++
                    found = true
                    log("[选群] ✓ 已勾选: $groupName (83, ${rect.centerY()})")
                    GestureHelper.delay(500)
                    break
                }

                // 没找到 → 向下滑动继续找
                if (scroll < 5) {
                    val metrics = service.resources.displayMetrics
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

        // 点击底部"确定(N)"按钮，进入发送确认弹窗
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
     * 切换到多选模式
     *
     * 单选 vs 多选的确定性区别（dump 实证）：
     *   单选模式 tab: "最近聊天" + "创建聊天" + "转到微信"
     *   多选模式 tab: "最近聊天" + "从通讯录选择"
     *
     * 策略：
     *   1. 先检查是否已在多选模式（"从通讯录选择"存在）
     *   2. 如不在，点右上角对勾按钮 bounds=[810,112,945,247]
     *   3. 验证："从通讯录选择"出现 或 "创建聊天"消失
     */
    private fun switchToMultiSelect(service: WeWorkAccessibilityService): Boolean {
        // 防御：企微不在前台时不操作，避免在桌面上乱点
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

        // 取 left 最大的（搜索在对勾左边，对勾在最右边）
        val btn = clickables.maxByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.left
        } ?: return false

        val rect = Rect()
        btn.getBoundsInScreen(rect)
        log("[选群] 点击对勾按钮 (${rect.centerX()}, ${rect.centerY()})，共找到 ${clickables.size} 个候选")
        service.clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())

        // 等待多选模式生效（最多 3 秒），替代硬等 800ms 后检查一次
        val switched = NodeFinder.waitForNode(service, 3000) { r ->
            // "从通讯录选择" 出现 = 多选模式
            NodeFinder.findByText(r, "从通讯录选择")
        }
        if (switched != null) {
            log("[选群] ✓ 多选模式已激活（找到'从通讯录选择'）")
            return true
        }

        // 备选验证："创建聊天"消失 + "最近聊天"仍在 = 也算成功
        val checkRoot = service.getRootNode()
        if (checkRoot != null
            && NodeFinder.findByText(checkRoot, "创建聊天") == null
            && NodeFinder.findByText(checkRoot, "最近聊天") != null) {
            log("[选群] ✓ 多选模式已激活（'创建聊天'已消失）")
            return true
        }

        log("[选群] 点击对勾后未切换到多选模式")
        return false
    }

    /**
     * 处理 ForwardDialogUtil 发送确认弹窗
     *
     * 此时"确定(N)"已由 selectTargetGroups() 点击完成，
     * 这里只处理弹窗中的"发送(N)"按钮。
     *
     * dump 实证：text="发送(5)" bounds=[681,1478,900,1579] clickable
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
     * 长按后弹出的菜单是独立 window，getRootNode() 可能拿不到
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
                sb.appendLine("--- 窗口 ${i+1} (${rect.width()}x${rect.height()}) ---")
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
