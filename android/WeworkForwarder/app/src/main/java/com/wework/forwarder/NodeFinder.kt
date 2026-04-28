package com.wework.forwarder

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 控件查找工具
 *
 * 封装 AccessibilityNodeInfo 的查找操作，
 * 对应 AutoJs6 的 text().findOne()、id().findOne()、className().find() 等
 */
object NodeFinder {

    /** 控件树递归深度上限，防止异常控件树（自引用/极深嵌套）导致 StackOverflow */
    private const val MAX_DEPTH = 50

    /**
     * 通过文本查找控件
     */
    fun findByText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        root ?: return null
        val nodes = root.findAccessibilityNodeInfosByText(text)
        // findAccessibilityNodeInfosByText 是模糊匹配，需要精确过滤
        return nodes.firstOrNull { it.text?.toString() == text }
    }

    /**
     * 通过文本模糊查找（包含即可）
     */
    fun findByTextContains(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        root ?: return null
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull { it.text?.toString()?.contains(text) == true }
    }

    /**
     * 通过文本正则查找
     */
    fun findByTextRegex(root: AccessibilityNodeInfo?, pattern: Regex): AccessibilityNodeInfo? {
        root ?: return null
        return findFirst(root) { node ->
            node.text?.toString()?.let { pattern.matches(it) } == true
        }
    }

    /**
     * 通过 viewId 查找（需要完整 ID，如 "com.tencent.wework:id/xxx"）
     */
    fun findById(root: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
        root ?: return null
        val fullId = if (viewId.contains(":")) viewId else "${Config.WEWORK_PACKAGE}:id/$viewId"
        val nodes = root.findAccessibilityNodeInfosByViewId(fullId)
        return nodes.firstOrNull()
    }

    /**
     * 通过 className 查找第一个匹配的控件
     */
    fun findByClassName(root: AccessibilityNodeInfo?, className: String): AccessibilityNodeInfo? {
        root ?: return null
        return findFirst(root) { it.className?.toString() == className }
    }

    /**
     * 通过 className 查找所有匹配的控件
     */
    fun findAllByClassName(root: AccessibilityNodeInfo?, className: String): List<AccessibilityNodeInfo> {
        root ?: return emptyList()
        return findAll(root) { it.className?.toString() == className }
    }

    /**
     * 通过 contentDescription 查找
     */
    fun findByDesc(root: AccessibilityNodeInfo?, desc: String): AccessibilityNodeInfo? {
        root ?: return null
        return findFirst(root) { it.contentDescription?.toString() == desc }
    }

    /**
     * 递归查找第一个满足条件的控件
     */
    fun findFirst(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        return findFirstRecursive(root, predicate, 0)
    }

    private fun findFirstRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstRecursive(child, predicate, depth + 1)
            if (result != null) return result
        }
        return null
    }

    /**
     * 递归查找所有满足条件的控件
     */
    fun findAll(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findAllRecursive(root, predicate, results, 0)
        return results
    }

    private fun findAllRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        results: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        if (predicate(node)) results.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllRecursive(child, predicate, results, depth + 1)
        }
    }

    /**
     * 获取节点下所有文本内容
     */
    fun getAllTexts(node: AccessibilityNodeInfo): List<TextNode> {
        val results = mutableListOf<TextNode>()
        collectTexts(node, results, 0)
        return results
    }

    private fun collectTexts(node: AccessibilityNodeInfo, results: MutableList<TextNode>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val text = node.text?.toString()
        // 保留原始文本（不 trim），空格文本 " " 是企微头像占位符
        if (text != null && text.isNotEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            results.add(TextNode(text, rect))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, results, depth + 1)
        }
    }

    /**
     * 等待控件出现（轮询）
     */
    fun waitForNode(
        service: WeWorkAccessibilityService,
        timeout: Long = Config.PAGE_LOAD_TIMEOUT,
        finder: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            val root = service.getRootNode()
            if (root == null) {
                GestureHelper.delayExact(300)
                continue
            }
            val node = finder(root)
            if (node != null) return node
            GestureHelper.delayExact(300)
        }
        return null
    }

    /**
     * 点击控件（找到可点击的节点，或用坐标点击）
     */
    fun clickNode(service: WeWorkAccessibilityService, node: AccessibilityNodeInfo): Boolean {
        // 先尝试 performAction
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        // 回退到坐标点击
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return service.clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    /**
     * 向 EditText 控件设置文本
     */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * dump 控件树为字符串（调试用）
     */
    fun dumpTree(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        node ?: return ""
        if (depth > MAX_DEPTH) return "  ".repeat(depth) + "...(深度截断)\n"
        val sb = StringBuilder()
        val indent = "  ".repeat(depth)
        val rect = Rect()
        node.getBoundsInScreen(rect)

        sb.append(indent).append(node.className)
        node.viewIdResourceName?.let { sb.append(" | id=$it") }
        node.text?.let { sb.append(" | text=\"${it.toString().take(50)}\"") }
        node.contentDescription?.let { sb.append(" | desc=\"${it.toString().take(50)}\"") }
        sb.append(" | bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
        if (node.isClickable) sb.append(" | clickable")
        if (node.isScrollable) sb.append(" | scrollable")
        sb.appendLine()

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(dumpTree(child, depth + 1))
        }
        return sb.toString()
    }

    /**
     * 文本节点数据类
     */
    data class TextNode(val text: String, val bounds: Rect)
}
