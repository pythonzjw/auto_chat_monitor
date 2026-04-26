package com.wework.forwarder

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 页面导航模块
 *
 * 负责在企业微信中导航：进入群聊、返回消息列表等
 */
object Navigator {
    private const val TAG = "Navigator"

    private fun log(msg: String) {
        Log.d(TAG, msg)
        Config.uiLog?.invoke(msg)
    }

    /**
     * 回到企业微信消息列表首页
     */
    fun goToMessageList(service: WeWorkAccessibilityService): Boolean {
        log("[导航] 返回消息列表...")
        for (i in 0 until 10) {
            val root = service.getRootNode()
            // 检查底部"消息"标签
            val msgTab = NodeFinder.findByText(root, "消息")
            if (msgTab != null) {
                NodeFinder.clickNode(service, msgTab)
                GestureHelper.delay()
                log("[导航] ✓ 已到达消息列表页面")
                return true
            }
            // 没到，按返回键
            service.pressBack()
            GestureHelper.delay(500)
        }

        // 兜底：直接启动企业微信
        log("[导航] 无法返回消息列表，重新启动企业微信")
        launchWeWork(service)
        GestureHelper.delayExact(3000)

        val root = service.getRootNode()
        val msgTab = NodeFinder.findByText(root, "消息")
        if (msgTab != null) {
            NodeFinder.clickNode(service, msgTab)
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
    private fun findGroupInList(service: WeWorkAccessibilityService, metrics: DisplayMetrics, groupName: String): AccessibilityNodeInfo? {
        // 先在当前屏幕找
        var root = service.getRootNode()
        var group = NodeFinder.findByText(root, groupName)
        if (group != null) return group

        // 向下滑动几次查找
        for (i in 0 until 5) {
            GestureHelper.swipeDown(service, metrics)
            root = service.getRootNode()
            group = NodeFinder.findByText(root, groupName)
            if (group != null) return group
        }
        return null
    }

    /**
     * 通过搜索功能进入群聊
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

        // 在搜索框中输入群名
        val input = NodeFinder.waitForNode(service, 3000) { r ->
            NodeFinder.findByClassName(r, "android.widget.EditText")
        }
        if (input == null) {
            log("[导航] ✗ 找不到搜索输入框")
            service.pressBack()
            return false
        }
        NodeFinder.setText(input, groupName)
        GestureHelper.delayExact(1500)

        // 查找搜索结果中的群名
        val resultRoot = service.getRootNode()
        val result = NodeFinder.findByText(resultRoot, groupName)
            ?: NodeFinder.findByTextContains(resultRoot, groupName)
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
        if (titleCheck != null || inputCheck != null) {
            log("[导航] ✓ 已确认进入群聊: $groupName")
            return true
        }
        log("[导航] ✗ 进入群聊验证失败: $groupName")
        return false
    }

    /**
     * 退出当前群聊
     */
    fun exitGroup(service: WeWorkAccessibilityService): Boolean {
        service.pressBack()
        GestureHelper.delay(500)
        val root = service.getRootNode()
        if (NodeFinder.findByText(root, "消息") != null) return true
        // 再按一次
        service.pressBack()
        GestureHelper.delay(500)
        return NodeFinder.findByText(service.getRootNode(), "消息") != null
    }

    /**
     * 确保企业微信在前台
     */
    fun ensureWeWorkForeground(service: WeWorkAccessibilityService): Boolean {
        if (service.isInWeWork()) return true
        log("[导航] 企业微信不在前台，正在启动...")
        launchWeWork(service)
        GestureHelper.delayExact(3000)
        return service.isInWeWork()
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
