package com.wework.forwarder

import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log

/**
 * 消息转发模块
 *
 * 完整转发流程：
 *   1. 滚到底部 → 记录最后一条消息
 *   2. 定位第一条新消息
 *   3. 转发前保存书签（宁可漏不可重）
 *   4. 长按 → 多选 → 滚到底全选 → 转发
 *   5. 在选群页面逐个搜索目标群并勾选
 *   6. 发送
 */
object MessageForwarder {
    private const val TAG = "Forwarder"

    /**
     * 执行完整的转发流程
     * 前提：当前已在源群聊天页面
     */
    fun forwardNewMessages(service: WeWorkAccessibilityService, metrics: DisplayMetrics): Boolean {
        Log.i(TAG, "开始执行转发流程...")

        // 步骤1：先滚动到底部，确保能看到最新消息
        Log.d(TAG, "滚动到最新消息...")
        scrollToBottom(service, metrics)
        GestureHelper.delay(500)

        // 记录当前屏幕最后一条消息（转发完成后更新书签用）
        val lastMsg = MessageCollector.getLastMessage(service)

        // 步骤2：找到第一条新消息
        val bookmark = Storage.getBookmark()
        var firstNewMsg: Storage.Message? = null

        if (bookmark != null) {
            Log.d(TAG, "查找书签位置...")
            val bookmarkInfo = MessageCollector.scrollUpToBookmark(service, metrics, 30)
            if (bookmarkInfo != null) {
                Log.d(TAG, "找到书签，获取第一条新消息...")
                firstNewMsg = MessageCollector.getFirstNewMessage(service)
                if (firstNewMsg == null) {
                    GestureHelper.swipeDown(service, metrics)
                    firstNewMsg = MessageCollector.getFirstNewMessage(service)
                }
            } else {
                Log.d(TAG, "书签未找到，从当前可见的最早消息开始")
                val visibleMsgs = MessageCollector.collectVisibleMessages(service)
                firstNewMsg = visibleMsgs.firstOrNull()
            }
        } else {
            Log.d(TAG, "首次运行，回溯 ${Config.lookbackMinutes} 分钟的消息...")
            scrollUpForMinutes(service, metrics, Config.lookbackMinutes)
            val visibleMsgs = MessageCollector.collectVisibleMessages(service)
            firstNewMsg = visibleMsgs.firstOrNull()
        }

        if (firstNewMsg == null) {
            Log.d(TAG, "没有找到新消息，跳过转发")
            return false
        }

        // 步骤3：转发前先保存书签
        if (lastMsg != null) {
            Storage.saveBookmark(lastMsg.sender, lastMsg.content, lastMsg.time)
            Log.d(TAG, "书签已提前更新: ${lastMsg.sender}: ${lastMsg.content.take(20)}")
        }

        // 步骤4：长按第一条新消息
        Log.d(TAG, "长按第一条新消息: ${firstNewMsg.sender}: ${firstNewMsg.content.take(20)}")
        val msgElem = MessageCollector.findMessageElement(service, firstNewMsg)
        if (msgElem == null) {
            Log.e(TAG, "找不到第一条新消息的控件，无法长按")
            return false
        }
        val rect = Rect()
        msgElem.getBoundsInScreen(rect)
        service.longPressAt(rect.centerX().toFloat(), rect.centerY().toFloat())

        // 步骤5：点击"多选"
        val multiSelectBtn = NodeFinder.waitForNode(service, 3000) { root ->
            NodeFinder.findByText(root, "多选") ?: NodeFinder.findByDesc(root, "多选")
        }
        if (multiSelectBtn == null) {
            Log.e(TAG, "找不到'多选'按钮")
            dismissPopup(service, metrics)
            return false
        }
        NodeFinder.clickNode(service, multiSelectBtn)
        GestureHelper.delay(800)

        // 步骤6：滚动到底部并点"选择到这里"
        Log.d(TAG, "滚动到最新消息并全选...")
        if (!scrollAndSelectToHere(service, metrics)) {
            Log.e(TAG, "全选消息失败")
            exitMultiSelect(service)
            return false
        }

        // 步骤7：点击"转发"
        Log.d(TAG, "点击转发...")
        if (!clickForward(service)) {
            Log.e(TAG, "点击转发失败")
            exitMultiSelect(service)
            return false
        }

        // 步骤8：在选群页面勾选所有目标群
        Log.d(TAG, "选择目标群...")
        if (!selectTargetGroups(service)) {
            Log.e(TAG, "选择目标群失败")
            service.pressBack()
            exitMultiSelect(service)
            return false
        }

        // 步骤9：点击发送
        Log.d(TAG, "确认发送...")
        if (!confirmSend(service)) {
            Log.e(TAG, "确认发送失败")
            return false
        }

        // 保存消息记录到本地 JSON
        Storage.saveMessages(listOf(firstNewMsg))
        Log.i(TAG, "转发完成！")
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
                    Log.d(TAG, "已到达回溯时间边界")
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
                Log.d(TAG, "找到'选择到这里'按钮")
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
        Log.d(TAG, "未找到'选择到这里'按钮，可能只有一条新消息")
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
            Log.e(TAG, "找不到'转发'按钮")
            return false
        }
        NodeFinder.clickNode(service, forwardBtn)
        GestureHelper.delay(1000)

        // 可能弹出"逐条转发"/"合并转发"选项
        val newRoot = service.getRootNode()
        val oneByOne = NodeFinder.findByText(newRoot, "逐条转发")
        if (oneByOne != null) {
            NodeFinder.clickNode(service, oneByOne)
            GestureHelper.delay(800)
        }
        return true
    }

    /**
     * 在转发选群页面，逐个搜索并勾选目标群
     */
    private fun selectTargetGroups(service: WeWorkAccessibilityService): Boolean {
        var selectedCount = 0
        for ((idx, groupName) in Config.targetGroups.withIndex()) {
            Log.d(TAG, "搜索目标群 (${idx + 1}/${Config.targetGroups.size}): $groupName")

            val root = service.getRootNode()
            val searchInput = NodeFinder.findByClassName(root, "android.widget.EditText")
            if (searchInput == null) {
                Log.e(TAG, "找不到搜索输入框")
                continue
            }

            // 清空并输入群名
            NodeFinder.setText(searchInput, groupName)
            GestureHelper.delayExact(Config.SEARCH_WAIT_DELAY)

            // 在搜索结果中找到并勾选目标群
            val resultRoot = service.getRootNode()
            val result = NodeFinder.findByText(resultRoot, groupName)
            if (result != null) {
                NodeFinder.clickNode(service, result)
                selectedCount++
                Log.d(TAG, "已勾选: $groupName")
            } else {
                Log.w(TAG, "搜索结果中未找到: $groupName")
            }

            // 清空搜索框
            val input2 = NodeFinder.findByClassName(service.getRootNode(), "android.widget.EditText")
            if (input2 != null) NodeFinder.setText(input2, "")
            GestureHelper.delay(500)
        }

        Log.d(TAG, "共勾选 $selectedCount/${Config.targetGroups.size} 个目标群")
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
            Log.e(TAG, "找不到发送按钮")
            return false
        }
        NodeFinder.clickNode(service, sendBtn)
        GestureHelper.delayExact(1500)

        // 可能有二次确认弹窗
        val newRoot = service.getRootNode()
        val confirmAgain = NodeFinder.findByText(newRoot, "发送")
            ?: NodeFinder.findByText(newRoot, "确定")
        if (confirmAgain != null) {
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
}
