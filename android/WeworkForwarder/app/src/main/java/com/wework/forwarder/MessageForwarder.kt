package com.wework.forwarder

import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 消息转发模块
 *
 * v2.0 完整转发流程：
 *   1. 滚到底部
 *   2. 取倒数第 K 条作锚点(K = 调用方传入的未读徽章数)
 *   3. 长按 → 多选 → 滚到底全选 → 转发
 *   4. 在选群页面逐个搜索目标群并勾选（每批最多9个）
 *   5. 发送
 *   6. 如果目标群 > 9 个，回到源群重复 3-5
 */
object MessageForwarder {
    private const val TAG = "Forwarder"

    private enum class EdgeZone {
        TOP,
        BOTTOM
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        Config.uiLog?.invoke(msg)
    }

    private fun stopped(): Boolean = !CollectorService.isRunning

    /**
     * v2.0: 执行完整的转发流程（支持分批）
     * 前提：当前已在源群聊天页面
     *
     * @param unreadCount 调用方读到的未读徽章数(由 Navigator.findUnreadCountForGroup 提供)
     *                    将被截断到 Config.maxForwardCount 上限
     */
    fun forwardNewMessages(
        service: WeWorkAccessibilityService,
        metrics: DisplayMetrics,
        unreadCount: Int
    ): Boolean {
        log("[转发] 开始执行转发流程,目标:转最后 $unreadCount 条...")

        val k = minOf(unreadCount, Config.maxForwardCount)
        if (k < unreadCount) {
            log("[转发] 徽章 $unreadCount 超过上限 ${Config.maxForwardCount},截断为 $k")
        }
        val isSingleMessage = k == 1

        // 先用未读数 K 定位锚点；分割线只是辅助信号，不再作为唯一兜底。
        log("[转发] 步骤1: 数当前屏幕消息...")
        var anchor = MessageCollector.getNthFromBottomIfEnough(service, metrics, k)
        var usedDivider = false
        var anchorSource = "当前屏幕倒数"
        if (anchor != null) {
            log("[转发] 屏幕消息 >= $k, 直接定位锚点")
        } else {
            log("[转发] 屏幕消息 < $k, 上滑找分割线...")
            anchor = MessageCollector.findFirstNewMessageByDivider(service, metrics, maxScrolls = 30)
            if (anchor != null) {
                usedDivider = true
                anchorSource = "分割线辅助"
            } else {
                log("[转发] 分割线不存在或找不到, 回到底部走 K 计数兜底 (K=$k)...")
                scrollToBottom(service, metrics)
                if (stopped()) return false
                GestureHelper.delay(500)
                anchor = MessageCollector.getNthFromBottomMessageRow(service, metrics, k)
                if (anchor != null) {
                    usedDivider = true
                    anchorSource = "K计数"
                }
            }
        }
        if (anchor == null) {
            log("[转发] ✗ 所有路径均失败")
            dumpOnFailure(service, "取锚点失败")
            return false
        }
        if (stopped()) return false
        val firstNewMsg = anchor.message
        log("[转发] 锚点来源: $anchorSource")
        log("[转发] 锚点: ${firstNewMsg.sender}: ${firstNewMsg.content.take(30)}")

        // 分批转发
        val batches = Config.targetGroups.chunked(Config.BATCH_SIZE)
        log("[转发] ${Config.targetGroups.size} 个目标群，分 ${batches.size} 批")

        for ((batchIdx, batch) in batches.withIndex()) {
            if (stopped()) return false
            log("[转发] === 第 ${batchIdx + 1}/${batches.size} 批（${batch.size}个群）===")

            // v2.4.12: 第 2 批起留在原群,不再退群再进群
            // 用文本指纹复定位锚点(分割线已被读消失,getNthFromBottom 也会因企微滚到底而失效)
            log("[转发] 步骤4: 长按消息...")
            val pressInfo: MessageCollector.FirstNewMessageInfo?
            if (batchIdx == 0) {
                pressInfo = anchor
            } else {
                pressInfo = MessageCollector.findAnchorByMessage(service, metrics, firstNewMsg, maxScrolls = 30)
                usedDivider = true  // 复定位后锚点离底部远,scrollAndSelectToHere 必须走滚动版本
            }
            if (pressInfo == null) {
                log("[转发] ✗ 取锚点失败,无法长按")
                dumpOnFailure(service, "找不到锚点_批${batchIdx + 1}")
                if (batchIdx == 0) return false else continue
            }
            // v2.3.0: ListView 子节点路径返回 node + rect=null, findBubbleRect 在 node 内找气泡
            val pressRect = pressInfo.rect ?: pressInfo.node?.let { findBubbleRect(it) }
            if (pressRect == null) {
                log("[转发] ✗ 锚点既无 rect 也无 node,无法定位长按坐标")
                dumpOnFailure(service, "锚点无定位信息_批${batchIdx + 1}")
                if (batchIdx == 0) return false else continue
            }
            if (!isSafeLongPressRect(pressRect, metrics)) {
                log("[转发] ✗ 锚点在危险区域，拒绝长按 bounds=$pressRect")
                dumpOnFailure(service, "锚点危险区域_批${batchIdx + 1}")
                if (batchIdx == 0) return false else continue
            }
            log("[转发] 长按坐标: (${pressRect.centerX()}, ${pressRect.centerY()})")
            service.longPressAt(pressRect.centerX().toFloat(), pressRect.centerY().toFloat())
            GestureHelper.delay(1200)

            // 步骤5：点击"多选"并确认已进入多选模式，避免弹出菜单未点中后继续扩选
            if (!clickMultiSelectWithVerify(service, metrics, batchIdx + 1)) {
                if (batchIdx == 0) return false else continue
            }

            // 步骤6：单条消息不做扩选，避免误点"选择到这里"把旧消息带上
            if (isSingleMessage) {
                log("[转发] 步骤6: 单条新消息，跳过'选择到这里'扩选")
            } else {
                // 分割线路径必须滑到底，屏幕直选路径可快速点击
                log("[转发] 步骤6: 滚动全选 (needScroll=$usedDivider)...")
                if (!scrollAndSelectToHere(service, metrics, needScroll = usedDivider)) {
                    log("[转发] ✗ 全选失败")
                    dumpOnFailure(service, "全选失败_批${batchIdx + 1}")
                    exitMultiSelect(service)
                    if (batchIdx == 0) return false else continue
                }
                log("[转发] ✓ 全选完成")
            }

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

    private fun isSafeLongPressRect(rect: Rect, metrics: DisplayMetrics): Boolean {
        val topSafe = (metrics.heightPixels * 0.18f).toInt()
        val bottomSafe = (metrics.heightPixels * 0.84f).toInt()
        return rect.centerY() in topSafe..bottomSafe
    }

    private fun clickMultiSelectWithVerify(
        service: WeWorkAccessibilityService,
        metrics: DisplayMetrics,
        batchNumber: Int
    ): Boolean {
        log("[转发] 步骤5: 查找'多选'...")
        repeat(2) { attempt ->
            val multiSelectBtn = waitForNodeInAllWindows(service, if (attempt == 0) 3000 else 1500) { root ->
                NodeFinder.findByText(root, "多选") ?: NodeFinder.findByDesc(root, "多选")
            }
            if (multiSelectBtn == null) {
                log("[转发] 第 ${attempt + 1} 次找不到'多选'按钮")
                return@repeat
            }

            val rect = Rect()
            multiSelectBtn.getBoundsInScreen(rect)
            log("[转发] 点击'多选' attempt=${attempt + 1} (${rect.centerX()}, ${rect.centerY()})")
            service.clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            GestureHelper.delay(900)

            if (isInMessageMultiSelectMode(service)) {
                log("[转发] ✓ 已进入消息多选模式")
                return true
            }
            log("[转发] 点击'多选'后未进入多选模式，准备重试")
        }

        log("[转发] ✗ 点击'多选'失败，终止本批，避免误选旧消息")
        dumpAllWindows(service, "多选点击未生效_批$batchNumber")
        dismissPopup(service, metrics)
        return false
    }

    private fun isInMessageMultiSelectMode(service: WeWorkAccessibilityService): Boolean {
        for (root in service.getAllRootNodes()) {
            val hasSelectToHere = NodeFinder.findByTextContains(root, "选择到这里") != null
                || NodeFinder.findAll(root) { node ->
                    node.contentDescription?.toString()?.contains("选择到这里") == true
                }.isNotEmpty()
            if (hasSelectToHere) return true

            val hasCancel = NodeFinder.findByText(root, "取消") != null
                || NodeFinder.findByDesc(root, "取消") != null
            val hasForward = NodeFinder.findByText(root, "转发") != null
                || NodeFinder.findByDesc(root, "转发") != null
            if (hasCancel && hasForward) return true
        }
        return false
    }

    private fun scrollToBottom(service: WeWorkAccessibilityService, metrics: DisplayMetrics) {
        // 滑动慢化后单次幅度 8%,需要更多轮才能到底
        repeat(8) {
            if (stopped()) return
            GestureHelper.swipeDown(service, metrics)
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
    private fun scrollAndSelectToHere(service: WeWorkAccessibilityService, metrics: DisplayMetrics, needScroll: Boolean = false): Boolean {
        if (!needScroll) {
            // 快速尝试: 消息全在屏幕内时,底部按钮已可见,不需要滑动
            GestureHelper.delay(300)
            val quickBtn = findSelectToHereDown(service, strict = false)
            if (quickBtn != null) {
                val qr = Rect()
                quickBtn.getBoundsInScreen(qr)
                if (qr.centerY() > metrics.widthPixels) {
                    log("[转发] 底部按钮已可见,直接点击 y=${qr.centerY()}")
                    service.clickAt(qr.centerX().toFloat(), qr.centerY().toFloat())
                    GestureHelper.delay(1000)
                    return true
                }
            }
        }

        var lastBottom = -1
        var lastChildCount = -1
        var stableCount = 0

        for (i in 0 until 20) {
            if (stopped()) return false

            GestureHelper.swipeDown(service, metrics)

            val root = service.getRootNode()
            val chatList = root?.let {
                NodeFinder.findByClassName(it, "android.widget.ListView")
                    ?: NodeFinder.findByClassName(it, "androidx.recyclerview.widget.RecyclerView")
            }
            val curChildCount = chatList?.childCount ?: 0

            // v2.4.0: 用最后一个 child 的 bounds.bottom 判断到底,不再依赖 OCR
            val curBottom = if (chatList != null && curChildCount > 0) {
                val lastChild = chatList.getChild(curChildCount - 1)
                val r = Rect()
                lastChild?.getBoundsInScreen(r)
                r.bottom
            } else 0

            var btn = findSelectToHereDown(service)
            if (btn == null) {
                log("[转发] 按钮不可见，swipeUp 1次恢复")
                GestureHelper.swipeUp(service, metrics)
                btn = findSelectToHereDown(service)
            }

            val stable = curBottom > 0
                    && curBottom == lastBottom
                    && curChildCount == lastChildCount
            if (stable) {
                stableCount++
                log("[转发] 列表稳定第 ${stableCount} 轮 (bottom=$curBottom, count=$curChildCount)")
                if (stableCount >= 2) {
                    val finalBtn = btn ?: findSelectToHereDown(service, strict = false)
                    if (finalBtn != null) {
                        val r = Rect()
                        finalBtn.getBoundsInScreen(r)
                        log("[转发] 到底了，点击'选择到这里' y=${r.centerY()}")
                        service.clickAt(r.centerX().toFloat(), r.centerY().toFloat())
                        GestureHelper.delay(1000)
                        return true
                    }
                }
            } else {
                stableCount = 0
            }
            lastBottom = curBottom
            lastChildCount = curChildCount
        }

        // 兜底：20 轮后最后一次尝试 (v1.9.4: 都用 strict=false,远超到底门槛)
        val btn = findSelectToHereDown(service, strict = false) ?: run {
            // 兜底再 swipeUp 一次找按钮
            GestureHelper.swipeUp(service, metrics)
            findSelectToHereDown(service, strict = false)
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
     * v1.9.4：strict=false 时跳过"上半屏"启发式——仅在调用方已通过其他信号
     *         (如列表稳定 N 轮)确认"已到底"时使用,否则保留 v1.6.1 行为
     */
    private fun findSelectToHereDown(
        service: WeWorkAccessibilityService,
        strict: Boolean = true
    ): AccessibilityNodeInfo? {
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
            if (strict && rect.centerY() < screenHeight / 2) {
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
     *   3. 从顶部逐屏扫描，当前屏命中的目标群立即勾选
     *   4. 点右下角蓝色"确定"按钮
     */
    private fun selectTargetGroups(service: WeWorkAccessibilityService, groups: List<String>): Boolean {
        data class ListContainer(
            val node: AccessibilityNodeInfo,
            val rect: Rect,
        )

        data class VisibleGroup(
            val name: String,
            val rect: Rect,
        )

        data class EdgeGroup(
            val name: String,
            val rect: Rect,
            val zone: EdgeZone,
        )

        data class InvalidGroup(
            val name: String,
            val rect: Rect,
            val reason: String,
        )

        fun readSelectedCount(root: AccessibilityNodeInfo?): Int? {
            val btn = NodeFinder.findByTextRegex(root, Regex("确定\\s*\\(\\d+\\)")) ?: return null
            val text = btn.text?.toString() ?: return null
            return Regex("\\d+").find(text)?.value?.toIntOrNull()
        }

        fun clickAndVerify(
            groupName: String,
            centerY: Int,
            beforeCount: Int?
        ): Boolean {
            repeat(2) { attempt ->
                service.clickAt(83f, centerY.toFloat())
                GestureHelper.delay(500)
                val afterCount = readSelectedCount(service.getRootNode())
                if (beforeCount != null && afterCount != null) {
                    if (afterCount >= beforeCount + 1) {
                        log("[选群] ✓ 已勾选: $groupName (83, $centerY) 计数 $beforeCount->$afterCount")
                        return true
                    }
                    log("[选群] 点击后计数未增长: $groupName ($beforeCount->$afterCount), attempt=${attempt + 1}")
                    return@repeat
                }
                // 兼容极少数场景：确定(N) 文本临时不可见，按点击成功处理，避免误阻断
                log("[选群] ✓ 已勾选: $groupName (83, $centerY) 计数不可读")
                return true
            }
            return false
        }

        fun clipRect(rect: Rect, container: Rect): Rect? {
            val clipped = Rect(
                maxOf(rect.left, container.left),
                maxOf(rect.top, container.top),
                minOf(rect.right, container.right),
                minOf(rect.bottom, container.bottom)
            )
            return if (clipped.left < clipped.right && clipped.top < clipped.bottom) clipped else null
        }

        fun findRecentChatList(root: AccessibilityNodeInfo, metrics: DisplayMetrics): ListContainer? {
            val screenRect = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            val candidates = NodeFinder.findAll(root) { node ->
                val cls = node.className?.toString()
                cls == "android.widget.ListView" || cls == "androidx.recyclerview.widget.RecyclerView"
            }
            return candidates.mapNotNull { node ->
                val rawRect = Rect()
                node.getBoundsInScreen(rawRect)
                val visibleRect = clipRect(rawRect, screenRect) ?: return@mapNotNull null
                if (visibleRect.width() < metrics.widthPixels * 0.6f) return@mapNotNull null
                if (visibleRect.height() < metrics.heightPixels * 0.2f) return@mapNotNull null
                ListContainer(node, visibleRect)
            }.maxByOrNull { it.rect.width() * it.rect.height() }
        }

        fun computeSafeZone(
            root: AccessibilityNodeInfo?,
            metrics: DisplayMetrics,
            listRect: Rect
        ): Pair<Int, Int> {
            val confirmBtn = NodeFinder.findByTextRegex(root, Regex("确定\\s*\\(\\d+\\)"))
                ?: NodeFinder.findByText(root, "确定")
            val confirmTop = Rect().let { rect ->
                confirmBtn?.getBoundsInScreen(rect)
                if (confirmBtn != null) rect.top else listRect.bottom
            }
            val topSafe = maxOf(
                listRect.top + 12,
                (metrics.heightPixels * 0.10f).toInt()
            )
            val bottomSafeCandidate = minOf(
                listRect.bottom - 12,
                confirmTop - 12,
                (metrics.heightPixels * 0.96f).toInt()
            )
            val bottomSafe = if (bottomSafeCandidate > topSafe) {
                bottomSafeCandidate
            } else {
                listRect.bottom - 12
            }
            return topSafe to maxOf(topSafe, bottomSafe)
        }

        fun collectVisibleTargets(
            listNode: AccessibilityNodeInfo,
            listRect: Rect,
            pending: Set<String>,
            topSafe: Int,
            bottomSafe: Int
        ): Triple<List<VisibleGroup>, List<EdgeGroup>, List<InvalidGroup>> {
            if (pending.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())
            val matchedNodes = NodeFinder.findAll(listNode) {
                val text = it.text?.toString()?.trim() ?: return@findAll false
                text in pending
            }.sortedWith(compareBy<AccessibilityNodeInfo>({ node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.centerY()
            }, { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.left
            }))

            val clickable = linkedMapOf<String, VisibleGroup>()
            val edge = linkedMapOf<String, EdgeGroup>()
            val invalid = linkedMapOf<String, InvalidGroup>()
            for (node in matchedNodes) {
                val text = node.text?.toString()?.trim() ?: continue
                val rawRect = Rect()
                node.getBoundsInScreen(rawRect)
                if (rawRect.left >= rawRect.right || rawRect.top >= rawRect.bottom) {
                    if (text !in clickable && text !in edge && text !in invalid) {
                        invalid[text] = InvalidGroup(text, rawRect, "bounds_invalid")
                    }
                    continue
                }
                val rect = clipRect(rawRect, listRect)
                if (rect == null) {
                    if (text !in clickable && text !in edge && text !in invalid) {
                        invalid[text] = InvalidGroup(text, rawRect, "outside_list")
                    }
                    continue
                }
                if (rect.width() < 40 || rect.height() < 12) {
                    if (text !in clickable && text !in edge && text !in invalid) {
                        invalid[text] = InvalidGroup(text, rawRect, "too_small")
                    }
                    continue
                }
                val centerY = rect.centerY()
                if (centerY in topSafe..bottomSafe) {
                    clickable[text] = VisibleGroup(text, rect)
                    edge.remove(text)
                    invalid.remove(text)
                    continue
                }
                if (text !in clickable && text !in edge) {
                    val zone = if (centerY < topSafe) EdgeZone.TOP else EdgeZone.BOTTOM
                    edge[text] = EdgeGroup(text, rect, zone)
                }
            }
            return Triple(clickable.values.toList(), edge.values.toList(), invalid.values.toList())
        }

        fun buildListSignature(listNode: AccessibilityNodeInfo, listRect: Rect): String {
            return NodeFinder.getAllTexts(listNode)
                .filter {
                    val text = it.text.trim()
                    if (text.isEmpty()) return@filter false
                    val clipped = clipRect(it.bounds, listRect) ?: return@filter false
                    clipped.width() >= 40 && clipped.height() >= 12
                }
                .take(24)
                .joinToString("|") {
                    val clipped = clipRect(it.bounds, listRect) ?: it.bounds
                    "${it.text.trim()}@${clipped.top}"
                }
        }

        fun formatEdgeTarget(target: EdgeGroup): String {
            val zone = if (target.zone == EdgeZone.TOP) "topEdge" else "bottomEdge"
            return "${target.name}@$zone[${target.rect.left},${target.rect.top},${target.rect.right},${target.rect.bottom}]"
        }

        fun formatInvalidTarget(target: InvalidGroup): String {
            return "${target.name}@${target.reason}[${target.rect.left},${target.rect.top},${target.rect.right},${target.rect.bottom}]"
        }

        fun pickEdgeAdjustmentTarget(edgeTargets: List<EdgeGroup>): EdgeGroup? {
            if (edgeTargets.isEmpty()) return null
            val topTargets = edgeTargets.filter { it.zone == EdgeZone.TOP }
            val bottomTargets = edgeTargets.filter { it.zone == EdgeZone.BOTTOM }
            val chosenZone = when {
                bottomTargets.size > topTargets.size -> EdgeZone.BOTTOM
                topTargets.size > bottomTargets.size -> EdgeZone.TOP
                bottomTargets.isNotEmpty() -> EdgeZone.BOTTOM
                else -> EdgeZone.TOP
            }
            val candidates = if (chosenZone == EdgeZone.BOTTOM) bottomTargets else topTargets
            return if (chosenZone == EdgeZone.BOTTOM) {
                candidates.minByOrNull { it.rect.centerY() }
            } else {
                candidates.maxByOrNull { it.rect.centerY() }
            }
        }

        fun moveToRecentChatTop(service: WeWorkAccessibilityService, metrics: DisplayMetrics): Boolean {
            var lastSignature: String? = null
            var stableCount = 0
            repeat(25) { attempt ->
                if (stopped()) return false
                val root = service.getRootNode()
                if (root == null) {
                    GestureHelper.delay(300)
                    return@repeat
                }
                val list = findRecentChatList(root, metrics) ?: run {
                    GestureHelper.delay(300)
                    return@repeat
                }
                val signature = buildListSignature(list.node, list.rect)
                if (signature.isNotEmpty() && signature == lastSignature) {
                    stableCount++
                    if (stableCount >= 2) {
                        log("[选群] 最近聊天已回到顶部 (attempt=${attempt + 1})")
                        return true
                    }
                } else {
                    stableCount = 0
                }
                lastSignature = signature
                GestureHelper.swipeUp(service, metrics)
                GestureHelper.delay(400)
            }
            log("[选群] 未确认已到顶部，继续从当前列表位置开始扫描")
            return true
        }

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

        // 进选群页后先回到最近聊天顶部，避免从中段开始导致前面的群名被漏
        val metrics0 = service.resources.displayMetrics
        if (!moveToRecentChatTop(service, metrics0)) {
            log("[选群] 回顶部过程被中断")
            return false
        }
        GestureHelper.delay(500)

        // 3. 目标群名去重后，从顶部逐屏扫描；当前屏命中即勾选，不依赖输入顺序
        val pending = linkedSetOf<String>()
        val duplicates = mutableListOf<String>()
        for (rawName in groups) {
            val groupName = rawName.trim()
            if (groupName.isEmpty()) continue
            if (!pending.add(groupName)) duplicates.add(groupName)
        }
        if (duplicates.isNotEmpty()) {
            log("[选群] 输入存在重复群名，已按首次出现去重: ${duplicates.distinct().joinToString(", ")}")
        }

        var selectedCount = 0
        var lastPageSignature: String? = null
        val edgeAdjustAttempts = mutableMapOf<String, Int>()
        var scroll = 0
        var bottomStableCount = 0
        while (scroll <= 60) {
            if (stopped()) return selectedCount > 0
            if (pending.isEmpty()) break

            val root = service.getRootNode()
            if (root == null) {
                log("[选群] scroll=$scroll 获取根节点失败，继续下滑重试")
                if (scroll >= 60) break
                GestureHelper.delay(300)
                scroll++
                continue
            }
            val metrics = service.resources.displayMetrics
            val list = findRecentChatList(root, metrics)
            if (list == null) {
                log("[选群] scroll=$scroll 找不到最近聊天列表")
                if (scroll >= 60) break
                GestureHelper.swipeDown(service, metrics)
                GestureHelper.delay(500)
                scroll++
                continue
            }
            val (topSafe, bottomSafe) = computeSafeZone(root, metrics, list.rect)
            val (visibleTargets, edgeTargets, invalidTargets) = collectVisibleTargets(
                list.node,
                list.rect,
                pending,
                topSafe,
                bottomSafe
            )

            if (visibleTargets.isNotEmpty()) {
                log("[选群] scroll=$scroll 命中屏内目标: ${visibleTargets.joinToString(", ") { it.name }}")
            } else if (edgeTargets.isNotEmpty()) {
                log("[选群] scroll=$scroll 命中边缘目标: ${edgeTargets.joinToString(", ") { formatEdgeTarget(it) }}")
            } else {
                log("[选群] scroll=$scroll 本屏未命中目标，待选: ${pending.joinToString(", ")}")
            }
            if (invalidTargets.isNotEmpty()) {
                log("[选群] scroll=$scroll 忽略异常节点: ${invalidTargets.take(6).joinToString(", ") { formatInvalidTarget(it) }}")
            }

            var matchedThisScreen = 0
            for (target in visibleTargets) {
                if (stopped()) return selectedCount > 0
                if (target.name !in pending) continue

                val beforeCount = readSelectedCount(service.getRootNode())
                if (clickAndVerify(target.name, target.rect.centerY(), beforeCount)) {
                    pending.remove(target.name)
                    selectedCount++
                    matchedThisScreen++
                } else {
                    log("[选群] 勾选未生效,保留待选: ${target.name}")
                }
            }

            if (pending.isEmpty()) break

            if (matchedThisScreen == 0 && edgeTargets.isNotEmpty()) {
                val adjustTarget = pickEdgeAdjustmentTarget(edgeTargets)
                if (adjustTarget != null) {
                    val currentAttempts = edgeAdjustAttempts[adjustTarget.name] ?: 0
                    if (currentAttempts < 2) {
                        edgeAdjustAttempts[adjustTarget.name] = currentAttempts + 1
                        if (adjustTarget.zone == EdgeZone.TOP) {
                            log("[选群] ${adjustTarget.name} 命中标题区 bounds=[${adjustTarget.rect.left},${adjustTarget.rect.top},${adjustTarget.rect.right},${adjustTarget.rect.bottom}]，swipeDown 反向微调 (${currentAttempts + 1}/2)")
                            GestureHelper.swipeDown(service, metrics)
                        } else {
                            log("[选群] ${adjustTarget.name} 命中底栏区 bounds=[${adjustTarget.rect.left},${adjustTarget.rect.top},${adjustTarget.rect.right},${adjustTarget.rect.bottom}]，swipeUp 反向微调 (${currentAttempts + 1}/2)")
                            GestureHelper.swipeUp(service, metrics)
                        }
                        GestureHelper.delay(800)
                        lastPageSignature = null
                        continue
                    }
                    log("[选群] ${adjustTarget.name} 连续边缘微调 2 次仍未进入安全区，继续向下扫描")
                }
            }

            val screenSignature = buildListSignature(list.node, list.rect)
            bottomStableCount = if (matchedThisScreen == 0 && edgeTargets.isEmpty() && screenSignature == lastPageSignature) {
                bottomStableCount + 1
            } else {
                0
            }
            if (bottomStableCount >= 2) {
                log("[选群] 最近聊天已扫到底部，停止扫描")
                break
            }
            lastPageSignature = screenSignature

            if (scroll >= 60) break
            GestureHelper.swipeDown(service, metrics)
            GestureHelper.delay(800)  // 等列表惯性停稳，避免重复扫描同一屏
            scroll++
        }

        if (pending.isNotEmpty()) {
            log("[选群] ✗ 未找到: ${pending.joinToString(", ")}")
            val root = service.getRootNode()
            if (root != null) {
                val allTexts = NodeFinder.getAllTexts(root)
                val summary = allTexts.filter { it.text.trim().isNotEmpty() }
                    .take(10).joinToString(", ") { "\"${it.text.take(20)}\"" }
                log("[选群] 页面文本: $summary")
            }
        }

        log("[选群] 共勾选 $selectedCount/${pending.size + selectedCount}")

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

        // 用 screenWidth 算标题栏范围(heightPixels 可能不含导航栏,但 bounds 包含状态栏)
        val sw = metrics.widthPixels
        val titleTop = (sw * 0.074).toInt()       // 1080 → 80
        val titleBottom = (sw * 0.241).toInt()    // 1080 → 260
        val maxBtnSize = (sw * 0.185).toInt()     // 1080 → 200
        val clickables = NodeFinder.findAll(root) { node ->
            if (!node.isClickable) return@findAll false
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top >= titleTop && rect.bottom <= titleBottom
                    && rect.left > halfWidth
                    && rect.width() < maxBtnSize
                    && rect.height() < maxBtnSize
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

    /**
     * 在 ListView 行内挑出适合长按的"气泡节点"矩形
     *
     * 背景:getNthFromBottomMessage 返回的 anchor.node 是 ListView 整行 RelativeLayout,
     * 全宽 (0~screenWidth);其几何中心落在头像与气泡之间空白处,企微长按不响应。
     *
     * v2.0.2: 旧策略 width>=200 误杀短文本气泡(如 "不是" 宽 183),兜底落到时间栏 "23:19"。
     *         改为按 className 排除头像 ImageView,保留所有 clickable 后代取最大。
     *         文本兜底加时间格式过滤。
     */
    private fun findBubbleRect(listItem: AccessibilityNodeInfo): Rect {
        val candidates = NodeFinder.findAll(listItem) {
            it.isClickable
                && it != listItem
                && it.className?.toString() != "android.widget.ImageView"  // 头像
        }.map { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            r
        }.filter { it.height() >= 30 && it.width() >= 80 }  // 真实气泡至少 80 宽,排除小按钮
        val largest = candidates.maxByOrNull { it.width() * it.height() }
        if (largest != null) {
            log("[长按] 选中气泡 bounds=$largest (clickable, ${candidates.size} 候选)")
            return largest
        }
        // 兜底1:任意非空 TextView 的 bounds (排除时间标签 "23:19" / "9:03")
        val timeRegex = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
        val textRect = NodeFinder.getAllTexts(listItem)
            .firstOrNull {
                it.text.isNotBlank() && !timeRegex.matches(it.text.trim())
            }?.bounds
        if (textRect != null) {
            log("[长按] 选中文本兜底 bounds=$textRect")
            return textRect
        }
        // 兜底2:行节点自己
        val fallback = Rect()
        listItem.getBoundsInScreen(fallback)
        log("[长按] 兜底用行节点 bounds=$fallback")
        return fallback
    }
}
