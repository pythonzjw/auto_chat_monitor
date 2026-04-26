package com.wework.forwarder

import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import java.io.File

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

    /**
     * 执行完整的转发流程（支持分批）
     * 前提：当前已在源群聊天页面
     */
    fun forwardNewMessages(service: WeWorkAccessibilityService, metrics: DisplayMetrics): Boolean {
        log("[转发] 开始执行转发流程...")

        // 步骤1：先滚动到底部，确保能看到最新消息
        log("[转发] 步骤1: 滚动到最新消息...")
        scrollToBottom(service, metrics)
        GestureHelper.delay(500)

        // 记录当前屏幕最后一条消息（转发完成后更新书签用）
        val lastMsg = MessageCollector.getLastMessage(service)
        log("[转发] 最后一条消息: ${lastMsg?.let { "${it.sender}: ${it.content.take(30)}" } ?: "无"}")

        // 步骤2：找到第一条新消息
        log("[转发] 步骤2: 定位第一条新消息...")
        val bookmark = Storage.getBookmark()
        var firstNewMsg: Storage.Message? = null

        if (bookmark != null) {
            log("[转发] 有书签，向上滚动查找书签位置...")
            val bookmarkInfo = MessageCollector.scrollUpToBookmark(service, metrics, 30)
            if (bookmarkInfo != null) {
                log("[转发] 找到书签(位置${bookmarkInfo.index}/${bookmarkInfo.totalOnScreen})，获取第一条新消息...")
                firstNewMsg = MessageCollector.getFirstNewMessage(service)
                if (firstNewMsg == null) {
                    GestureHelper.swipeDown(service, metrics)
                    firstNewMsg = MessageCollector.getFirstNewMessage(service)
                }
            } else {
                log("[转发] 书签未找到，从当前可见最早消息开始")
                val visibleMsgs = MessageCollector.collectVisibleMessages(service)
                log("[转发] 当前可见消息数: ${visibleMsgs.size}")
                firstNewMsg = visibleMsgs.firstOrNull()
            }
        } else {
            log("[转发] 首次运行，回溯 ${Config.lookbackMinutes} 分钟...")
            scrollUpForMinutes(service, metrics, Config.lookbackMinutes)
            val visibleMsgs = MessageCollector.collectVisibleMessages(service)
            log("[转发] 回溯采集到 ${visibleMsgs.size} 条消息")
            firstNewMsg = visibleMsgs.firstOrNull()
        }

        if (firstNewMsg == null) {
            log("[转发] ✗ 没有找到新消息，跳过转发")
            dumpOnFailure(service, "找不到新消息")
            return false
        }
        log("[转发] 第一条新消息: ${firstNewMsg.sender}: ${firstNewMsg.content.take(30)}")

        // 步骤3：转发前先保存书签
        if (lastMsg != null) {
            Storage.saveBookmark(lastMsg.sender, lastMsg.content, lastMsg.time)
            log("[转发] 步骤3: 书签已提前更新")
        }

        // 分批转发
        val batches = Config.targetGroups.chunked(Config.BATCH_SIZE)
        log("[转发] 目标群 ${Config.targetGroups.size} 个，分 ${batches.size} 批转发")

        for ((batchIdx, batch) in batches.withIndex()) {
            log("[转发] === 第 ${batchIdx + 1}/${batches.size} 批（${batch.size}个群）===")

            // 第2批起需要回源群重新操作
            if (batchIdx > 0) {
                log("[转发] 回到源群准备下一批...")
                Navigator.enterGroup(service, metrics, Config.sourceGroup)
                GestureHelper.delayExact(Config.enterGroupWaitSeconds * 1000L)
                scrollToBottom(service, metrics)
                GestureHelper.delay(500)
            }

            // 步骤4：长按第一条新消息
            log("[转发] 步骤4: 长按第一条新消息...")
            val msgElem = MessageCollector.findMessageElement(service, firstNewMsg)
            if (msgElem == null) {
                log("[转发] ✗ 找不到消息控件，无法长按")
                dumpOnFailure(service, "找不到消息控件_批次${batchIdx + 1}")
                if (batchIdx == 0) return false else continue
            }
            val rect = Rect()
            msgElem.getBoundsInScreen(rect)
            log("[转发] 长按坐标: (${rect.centerX()}, ${rect.centerY()})")
            service.longPressAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            GestureHelper.delay(800)

            // 步骤5：点击"多选"
            log("[转发] 步骤5: 查找'多选'按钮...")
            val multiSelectBtn = NodeFinder.waitForNode(service, 3000) { root ->
                NodeFinder.findByText(root, "多选") ?: NodeFinder.findByDesc(root, "多选")
            }
            if (multiSelectBtn == null) {
                log("[转发] ✗ 找不到'多选'按钮")
                dumpOnFailure(service, "找不到多选按钮_批次${batchIdx + 1}")
                dismissPopup(service, metrics)
                if (batchIdx == 0) return false else continue
            }
            NodeFinder.clickNode(service, multiSelectBtn)
            log("[转发] ✓ 已点击'多选'")
            GestureHelper.delay(800)

            // 步骤6：滚动到底部并点"选择到这里"
            log("[转发] 步骤6: 滚动到底部全选消息...")
            if (!scrollAndSelectToHere(service, metrics)) {
                log("[转发] ✗ 全选消息失败")
                dumpOnFailure(service, "全选失败_批次${batchIdx + 1}")
                exitMultiSelect(service)
                if (batchIdx == 0) return false else continue
            }
            log("[转发] ✓ 全选完成")

            // 步骤7：点击"转发"
            log("[转发] 步骤7: 点击'转发'...")
            if (!clickForward(service)) {
                log("[转发] ✗ 点击转发失败")
                dumpOnFailure(service, "转发按钮失败_批次${batchIdx + 1}")
                exitMultiSelect(service)
                if (batchIdx == 0) return false else continue
            }
            log("[转发] ✓ 已进入选群页面")

            // 步骤8：在选群页面勾选本批目标群
            log("[转发] 步骤8: 选择目标群 (${batch.joinToString(",")})...")
            if (!selectTargetGroups(service, batch)) {
                log("[转发] ✗ 选择目标群失败")
                dumpOnFailure(service, "选群失败_批次${batchIdx + 1}")
                service.pressBack()
                exitMultiSelect(service)
                if (batchIdx == 0) return false else continue
            }

            // 步骤9：点击发送
            log("[转发] 步骤9: 确认发送...")
            if (!confirmSend(service)) {
                log("[转发] ✗ 确认发送失败")
                dumpOnFailure(service, "发送失败_批次${batchIdx + 1}")
                if (batchIdx == 0) return false else continue
            }
            log("[转发] ✓ 第 ${batchIdx + 1} 批发送成功")

            // 批间随机延时
            if (batchIdx < batches.size - 1) {
                val batchDelay = 2000L + (Math.random() * 3000).toLong()
                log("[转发] 等待 ${batchDelay / 1000} 秒后继续下一批...")
                GestureHelper.delayExact(batchDelay)
            }
        }

        // 保存消息记录到本地 JSON
        Storage.saveMessages(listOf(firstNewMsg))
        log("[转发] ✓ 全部转发完成！")
        return true
    }

    // ===== 内部方法 =====

    private fun scrollToBottom(service: WeWorkAccessibilityService, metrics: DisplayMetrics) {
        repeat(5) { GestureHelper.swipeDown(service, metrics) }
    }

    private fun scrollUpForMinutes(service: WeWorkAccessibilityService, metrics: DisplayMetrics, minutes: Int) {
        for (i in 0 until 50) {
            val messages = MessageCollector.collectVisibleMessages(service)
            for (msg in messages) {
                val msgTime = TimeParser.parseMessageTime(msg.time)
                if (msgTime != null && !TimeParser.isWithinLookback(msgTime, minutes)) {
                    log("[转发] 已到达回溯时间边界")
                    return
                }
            }
            GestureHelper.swipeUp(service, metrics)
        }
    }

    /**
     * 在多选模式下，滚动到底部并点击"选择到这里"
     */
    private fun scrollAndSelectToHere(service: WeWorkAccessibilityService, metrics: DisplayMetrics): Boolean {
        for (i in 0 until 10) {
            GestureHelper.swipeDown(service, metrics)
            val root = service.getRootNode()
            val selectBtn = NodeFinder.findByText(root, "选择到这里")
                ?: NodeFinder.findByTextContains(root, "选择到这里")
                ?: NodeFinder.findByDesc(root, "选择到这里")
            if (selectBtn != null) {
                log("[转发] 找到'选择到这里'按钮")
                NodeFinder.clickNode(service, selectBtn)
                GestureHelper.delay(1000)
                return true
            }
        }

        // 再找一次
        val root = service.getRootNode()
        val selectBtn = NodeFinder.findByText(root, "选择到这里")
            ?: NodeFinder.findByTextContains(root, "选择到这里")
            ?: NodeFinder.findByDesc(root, "选择到这里")
        if (selectBtn != null) {
            NodeFinder.clickNode(service, selectBtn)
            GestureHelper.delay(1000)
            return true
        }

        // 可能只有一条消息，长按时已选中
        log("[转发] 未找到'选择到这里'，可能只有一条新消息（已选中）")
        return true
    }

    /**
     * 点击转发按钮
     */
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

        // 可能弹出"逐条转发"/"合并转发"选项
        val newRoot = service.getRootNode()
        val oneByOne = NodeFinder.findByText(newRoot, "逐条转发")
        if (oneByOne != null) {
            log("[转发] 选择'逐条转发'")
            NodeFinder.clickNode(service, oneByOne)
            GestureHelper.delay(800)
        }
        return true
    }

    /**
     * 在转发选群页面，逐个搜索并勾选目标群（接收批次列表）
     */
    private fun selectTargetGroups(service: WeWorkAccessibilityService, groups: List<String>): Boolean {
        var selectedCount = 0
        for ((idx, groupName) in groups.withIndex()) {
            log("[选群] (${idx + 1}/${groups.size}) 搜索: $groupName")

            val root = service.getRootNode()
            val searchInput = NodeFinder.findByClassName(root, "android.widget.EditText")
            if (searchInput == null) {
                log("[选群] ✗ 找不到搜索输入框")
                dumpOnFailure(service, "选群页面无搜索框")
                continue
            }

            // 清空并输入群名
            NodeFinder.setText(searchInput, groupName)
            GestureHelper.delayExact(Config.SEARCH_WAIT_DELAY)

            // 在搜索结果中找到并勾选目标群
            val resultRoot = service.getRootNode()
            // 先精确匹配
            var result = NodeFinder.findByText(resultRoot, groupName)
            // 失败则尝试包含匹配
            if (result == null) {
                result = NodeFinder.findByTextContains(resultRoot, groupName)
                if (result != null) {
                    log("[选群] 精确匹配失败，模糊匹配到: ${result.text}")
                }
            }

            if (result != null) {
                NodeFinder.clickNode(service, result)
                selectedCount++
                log("[选群] ✓ 已勾选: $groupName")
            } else {
                log("[选群] ✗ 搜索结果中未找到: $groupName")
                // dump 搜索结果页面帮助诊断
                val allTexts = NodeFinder.getAllTexts(resultRoot ?: service.getRootNode()!!)
                val textSummary = allTexts.take(10).joinToString(", ") { "\"${it.text.take(20)}\"" }
                log("[选群] 当前页面文本: $textSummary")
            }

            // 清空搜索框
            val input2 = NodeFinder.findByClassName(service.getRootNode(), "android.widget.EditText")
            if (input2 != null) NodeFinder.setText(input2, "")
            GestureHelper.delay(500)
        }

        log("[选群] 共勾选 $selectedCount/${groups.size} 个目标群")
        return selectedCount > 0
    }

    /**
     * 点击确认发送
     */
    private fun confirmSend(service: WeWorkAccessibilityService): Boolean {
        val root = service.getRootNode()
        val sendBtn = NodeFinder.findByTextRegex(root, Regex("发送\\s*\\(\\d+\\)"))
            ?: NodeFinder.findByText(root, "发送")
            ?: NodeFinder.findByText(root, "确定")
            ?: NodeFinder.findByText(root, "确认")
        if (sendBtn == null) {
            log("[转发] 找不到发送按钮")
            return false
        }
        log("[转发] 点击发送按钮: ${sendBtn.text}")
        NodeFinder.clickNode(service, sendBtn)
        GestureHelper.delayExact(1500)

        // 可能有二次确认弹窗
        val newRoot = service.getRootNode()
        val confirmAgain = NodeFinder.findByText(newRoot, "发送")
            ?: NodeFinder.findByText(newRoot, "确定")
        if (confirmAgain != null) {
            log("[转发] 点击二次确认")
            NodeFinder.clickNode(service, confirmAgain)
            GestureHelper.delay(1000)
        }
        return true
    }

    /**
     * 退出多选模式
     */
    private fun exitMultiSelect(service: WeWorkAccessibilityService) {
        val root = service.getRootNode()
        val cancelBtn = NodeFinder.findByText(root, "取消")
            ?: NodeFinder.findByDesc(root, "取消")
            ?: NodeFinder.findByDesc(root, "关闭")
        if (cancelBtn != null) {
            NodeFinder.clickNode(service, cancelBtn)
        } else {
            service.pressBack()
        }
        GestureHelper.delay(500)
    }

    /**
     * 关闭弹出菜单
     */
    private fun dismissPopup(service: WeWorkAccessibilityService, metrics: DisplayMetrics) {
        service.clickAt(metrics.widthPixels / 2f, metrics.heightPixels * 0.1f)
        GestureHelper.delay(500)
    }

    /**
     * 失败时自动 dump 控件树到文件
     */
    private fun dumpOnFailure(service: WeWorkAccessibilityService, reason: String) {
        try {
            val root = service.getRootNode() ?: run {
                log("[诊断] 无法获取根节点，跳过 dump")
                return
            }
            val dump = NodeFinder.dumpTree(root)
            val dir = File(Config.DATA_DIR)
            if (!dir.exists()) dir.mkdirs()
            val ts = System.currentTimeMillis()
            val safeReason = reason.replace(Regex("[^a-zA-Z0-9_\u4e00-\u9fa5]"), "_")
            val file = File(Config.DATA_DIR + "dump_${safeReason}_$ts.txt")
            val header = buildString {
                appendLine("失败原因: $reason")
                appendLine("包名: ${service.currentPackage}")
                appendLine("Activity: ${service.currentActivity}")
                appendLine("时间: ${Storage.now()}")
                appendLine("==============================")
            }
            file.writeText(header + dump)
            log("[诊断] 控件树已保存: ${file.name}")
        } catch (e: Exception) {
            log("[诊断] dump 失败: ${e.message}")
        }
    }
}
