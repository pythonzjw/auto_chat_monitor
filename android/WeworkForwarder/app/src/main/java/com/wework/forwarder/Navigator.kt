package com.wework.forwarder

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 页面导航模块
 *
 * 负责在企业微信中导航：进入群聊、返回消息列表等。
 */
object Navigator {
    private const val TAG = "Navigator"

    private fun log(msg: String) {
        Log.d(TAG, msg)
        Config.uiLog?.invoke(msg)
    }

    /**
     * 回到企业微信消息列表首页。
     *
     * 修复：
     * 1. 每次 pressBack 前先检查 isRunning，避免停止后继续操作
     * 2. 添加退出检测：返回键连按导致企微退到桌面时立即重新启动，
     *    而不是在桌面上继续盲目 pressBack
     * 3. 等待企微重启加入动态轮询，不再依赖固定 3 秒
     */
    fun goToMessageList(service: WeWorkAccessibilityService): Boolean {
        log("[导航] 返回消息列表...")

        for (i in 0 until 10) {
            if (!CollectorService.isRunning) return false

            val root = service.getRootNode()

            // 找到底部"消息"tab → 点击即可
            val msgTab = NodeFinder.findByText(root, "消息")
            if (msgTab != null && isBottomTab(service, msgTab)) {
                NodeFinder.clickNode(service, msgTab)
                GestureHelper.delay(500)
                log("[导航] ✓ 已到达消息列表页面")
                return true
            }

            // 企微不在前台（已被按返回键退到桌面）→ 直接重启，不再继续 pressBack
            if (!service.isInWeWork()) {
                log("[导航] 企微已退到后台，重新启动...")
                launchWeWork(service)
                // 动态等待企微回到前台（最多 5 秒）
                // 加 isBottomTab 过滤，避免误点会话列表中名字含"消息"的群条目
                val ready = NodeFinder.waitForNode(service, 5000) { r ->
                    NodeFinder.findByText(r, "消息")?.takeIf { isBottomTab(service, it) }
                }
                if (ready != null) {
                    NodeFinder.clickNode(service, ready)
                    log("[导航] ✓ 企微重启后已到消息列表")
                    return true
                }
                log("[导航] ✗ 企微重启后仍未就绪")
                return false
            }

            service.pressBack()
            GestureHelper.delay(500)
        }

        // 兜底：直接启动企业微信
        log("[导航] 无法返回消息列表，重新启动企业微信")
        launchWeWork(service)

        // 动态等待（最多 6 秒），不依赖固定延时
        val msgTab = NodeFinder.waitForNode(service, 6000) { r ->
            NodeFinder.findByText(r, "消息")?.takeIf { isBottomTab(service, it) }
        }
        if (msgTab != null) {
            NodeFinder.clickNode(service, msgTab)
            log("[导航] ✓ 重启后成功到达消息列表")
            return true
        }

        log("[导航] ✗ 返回消息列表失败")
        return false
    }

    /**
     * 从消息列表进入指定群聊
     */
    fun enterGroup(service: WeWorkAccessibilityService, metrics: DisplayMetrics, groupName: String): Boolean {
        log("[导航] 进入群聊: $groupName")

        if (!goToMessageList(service)) return false
        GestureHelper.delay(500)

        // 先在当前屏幕查找群名
        var group = findGroupInList(service, metrics, groupName)
        if (group != null) {
            NodeFinder.clickNode(service, group)
            log("[导航] ✓ 已点击进入群聊: $groupName")
            GestureHelper.delayExact(Config.enterGroupWaitSeconds * 1000L)
            return verifyInChatPage(service, groupName)
        }

        // 没找到，尝试搜索
        log("[导航] 列表中未找到，尝试搜索: $groupName")
        return searchAndEnterGroup(service, groupName)
    }

    /**
     * 在消息列表中查找群（包含向下滚动查找）
     */
    private fun findGroupInList(
        service: WeWorkAccessibilityService,
        metrics: DisplayMetrics,
        groupName: String
    ): AccessibilityNodeInfo? {
        var root = service.getRootNode()
        var group = NodeFinder.findByText(root, groupName)
        if (group != null) return group

        for (i in 0 until 5) {
            GestureHelper.swipeDown(service, metrics)
            root = service.getRootNode()
            group = NodeFinder.findByText(root, groupName)
            if (group != null) return group
        }
        return null
    }

    /**
     * 通过搜索功能进入群聊。
     *
     * 修复：ACTION_SET_TEXT 不触发企微搜索 TextWatcher。
     * 改为：输入文字后模拟粘贴（ACTION_PASTE）或补发键盘事件来触发联想，
     * 同时延长等待时间确保搜索结果加载。
     */
    private fun searchAndEnterGroup(service: WeWorkAccessibilityService, groupName: String): Boolean {
        val root = service.getRootNode()

        // 点击搜索入口
        val searchBox = NodeFinder.findById(root, "ici")
            ?: NodeFinder.findByDesc(root, "搜索")
            ?: NodeFinder.findByText(root, "搜索")
        if (searchBox == null) {
            log("[导航] ✗ 找不到搜索入口")
            return false
        }
        NodeFinder.clickNode(service, searchBox)
        GestureHelper.delay(1000)

        // 等待搜索输入框出现
        val input = NodeFinder.waitForNode(service, 3000) { r ->
            NodeFinder.findByClassName(r, "android.widget.EditText")
        }
        if (input == null) {
            log("[导航] ✗ 找不到搜索输入框")
            service.pressBack()
            return false
        }

        // 先点击输入框获取焦点
        NodeFinder.clickNode(service, input)
        GestureHelper.delay(300)

        // 用 ACTION_SET_TEXT 写入内容
        NodeFinder.setText(input, groupName)
        GestureHelper.delay(300)

        // 补发一次 ACTION_PASTE 触发 TextWatcher（企微搜索框需要输入事件才会联想）
        input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        GestureHelper.delayExact(Config.SEARCH_WAIT_DELAY)

        // 如果 PASTE 后还是没结果，再试一次 SET_TEXT
        var resultRoot = service.getRootNode()
        var result = NodeFinder.findByText(resultRoot, groupName)
            ?: NodeFinder.findByTextContains(resultRoot, groupName)

        if (result == null) {
            // 第二次尝试：清空再写一次
            NodeFinder.setText(input, "")
            GestureHelper.delay(200)
            NodeFinder.setText(input, groupName)
            GestureHelper.delayExact(Config.SEARCH_WAIT_DELAY)
            resultRoot = service.getRootNode()
            result = NodeFinder.findByText(resultRoot, groupName)
                ?: NodeFinder.findByTextContains(resultRoot, groupName)
        }

        if (result == null) {
            log("[导航] ✗ 搜索结果中未找到: $groupName")
            service.pressBack()
            service.pressBack()
            return false
        }
        NodeFinder.clickNode(service, result)
        GestureHelper.delayExact(Config.enterGroupWaitSeconds * 1000L)
        return verifyInChatPage(service, groupName)
    }

    /**
     * 验证是否成功进入了聊天页面
     */
    private fun verifyInChatPage(service: WeWorkAccessibilityService, groupName: String): Boolean {
        val root = service.getRootNode()
        val titleCheck = NodeFinder.findByText(root, groupName)
            ?: NodeFinder.findByTextContains(root, groupName)
        val inputCheck = NodeFinder.findByClassName(root, "android.widget.EditText")
        return if (titleCheck != null || inputCheck != null) {
            log("[导航] ✓ 已确认进入群聊: $groupName")
            true
        } else {
            log("[导航] ✗ 进入群聊验证失败: $groupName")
            false
        }
    }

    /**
     * 退出当前群聊
     */
    fun exitGroup(service: WeWorkAccessibilityService): Boolean {
        service.pressBack()
        GestureHelper.delay(500)
        val root = service.getRootNode()
        if (NodeFinder.findByText(root, "消息") != null) return true
        service.pressBack()
        GestureHelper.delay(500)
        return NodeFinder.findByText(service.getRootNode(), "消息") != null
    }

    /**
     * 确保企业微信在前台（动态等待，不依赖固定延时）
     */
    fun ensureWeWorkForeground(service: WeWorkAccessibilityService): Boolean {
        if (service.isInWeWork()) return true
        log("[导航] 企业微信不在前台，正在启动...")
        launchWeWork(service)
        // 动态等待企微回到前台，最多 6 秒
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 6000) {
            // 用户按停止时立即退出，不再空转 6 秒
            if (!CollectorService.isRunning) return false
            GestureHelper.delayExact(500)
            if (service.isInWeWork()) {
                log("[导航] ✓ 企微已在前台")
                return true
            }
        }
        log("[导航] ✗ 企微启动超时")
        return false
    }

    /**
     * 判断节点是否是底部导航 tab（避免把群名"消息"误认为 tab）
     *
     * 底部 tab 特征：y 坐标靠近屏幕底部（> 75% 屏幕高度）
     */
    private fun isBottomTab(service: WeWorkAccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val screenHeight = service.resources.displayMetrics.heightPixels
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return rect.centerY() > screenHeight * 0.75
    }

    /**
     * 启动企业微信
     */
    private fun launchWeWork(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(Config.WEWORK_PACKAGE)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动企业微信失败: ${e.message}")
        }
    }
}
