package com.wework.forwarder

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v2.1.0: 截屏 + MLKit OCR 识别屏幕消息
 *
 * 替代 MessageCollector.parseListItem 中"按头像锚点"的识别方式。
 * 头像被截断 / 同人连发 / 卡片头像异步加载失败 等场景下,
 * NodeInfo 路径会漏消息,OCR 直接读像素,只关心文字本身。
 *
 * 要求 Android 11+ (API 30) 才有 AccessibilityService.takeScreenshot()
 */
object OcrCollector {
    private const val TAG = "OcrCollector"

    private fun log(msg: String) {
        Log.d(TAG, msg)
        Config.uiLog?.invoke(msg)
    }

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private val executor by lazy { Executors.newSingleThreadExecutor() }

    data class OcrMessage(
        val sender: String,
        val content: String,
        val time: String,
        val rect: Rect,
    ) {
        fun toStorageMsg(): Storage.Message =
            Storage.Message(sender = sender, time = time, content = content, type = "text")
    }

    /**
     * 截屏 → OCR → 切分成消息列表。同步阻塞,失败返回 null。
     * 时间标签会更新内部 currentTime 但不入返回列表。
     */
    fun collectFromScreen(service: WeWorkAccessibilityService): List<OcrMessage>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("[OCR] 需要 Android 11+ (API 30+),当前 ${Build.VERSION.SDK_INT}")
            return null
        }

        val bitmap = takeScreenshot(service) ?: return null
        val text = recognizeText(bitmap) ?: return null

        val width = service.resources.displayMetrics.widthPixels
        val height = service.resources.displayMetrics.heightPixels
        val messages = splitToMessages(text, width, height)
        log("[OCR] 屏幕识别 ${messages.size} 条消息 (TextBlock 总数 ${text.textBlocks.size})")
        return messages
    }

    private fun takeScreenshot(service: WeWorkAccessibilityService): Bitmap? {
        val latch = CountDownLatch(1)
        var result: Bitmap? = null

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hwBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            // hardware bitmap 部分场景 OCR 不稳,copy 成软件 ARGB_8888 再用
                            result = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap?.recycle()
                            hwBuffer.close()
                        } catch (e: Exception) {
                            log("[OCR] 截屏转 Bitmap 失败: ${e.message}")
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        log("[OCR] 截屏失败 errorCode=$errorCode")
                        latch.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            log("[OCR] takeScreenshot 调用异常: ${e.message}")
            return null
        }

        if (!latch.await(3, TimeUnit.SECONDS)) {
            log("[OCR] 截屏超时(3s)")
            return null
        }
        return result
    }

    private fun recognizeText(bitmap: Bitmap): Text? {
        val latch = CountDownLatch(1)
        var result: Text? = null
        var error: Throwable? = null

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    result = text
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    error = e
                    latch.countDown()
                }
        } catch (e: Exception) {
            log("[OCR] OCR 调用异常: ${e.message}")
            bitmap.recycle()
            return null
        }

        // 首次跑要下载模型,留宽一点;之后正常都在 1s 内
        val ok = latch.await(15, TimeUnit.SECONDS)
        bitmap.recycle()
        if (!ok) {
            log("[OCR] OCR 超时(15s),模型可能未下载完成")
            return null
        }
        if (error != null) {
            log("[OCR] OCR 失败: ${error?.message}")
            return null
        }
        return result
    }

    // 时间标签:14:30 / 昨天 14:30 / 周一 14:30 / 12月25日 / 2025年12月25日 等
    private val timeRegex = Regex(
        """^(\d{1,2}:\d{2}|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天]|\d{1,2}月\d{1,2}日|\d{4}年\d{1,2}月\d{1,2}日)(\s+\d{1,2}:\d{2})?$"""
    )

    private fun splitToMessages(text: Text, screenWidth: Int, screenHeight: Int): List<OcrMessage> {
        val halfWidth = screenWidth / 2
        // 聊天区:顶部 10% 留给状态栏+标题栏,底部 15% 留给输入区
        val chatTop = (screenHeight * 0.10).toInt()
        val chatBottom = (screenHeight * 0.85).toInt()

        val blocks = text.textBlocks
            .mapNotNull { b -> b.boundingBox?.let { it to b } }
            .filter { (rect, _) -> rect.centerY() in chatTop..chatBottom }
            .sortedBy { (rect, _) -> rect.top }

        val messages = mutableListOf<OcrMessage>()
        var currentTime = ""
        // 时间标签后的第一条消息禁止与之前的合并(跨时间段不属于同气泡)
        var blockMergeAcrossTime = false

        for ((rect, block) in blocks) {
            val content = block.text.trim()
            if (content.isEmpty()) continue

            // 时间标签:水平居中 + 短文本 + 匹配时间正则
            val isCenteredX = rect.centerX() in (screenWidth * 35 / 100)..(screenWidth * 65 / 100)
            if (isCenteredX && content.length <= 16 && timeRegex.matches(content)) {
                currentTime = content
                blockMergeAcrossTime = true
                continue
            }

            val sender = if (rect.centerX() < halfWidth) "群成员" else "我"

            // 聚合判定:与上一条同侧 + 垂直间距 ≤ 当前文本块高度 → 同一气泡的另一行
            // 一条小程序卡片/多行文字消息会被 MLKit 切成多个 TextBlock,必须聚合,
            // 否则 25 个 block 会被当成 25 条消息,直接骗过滚动加载逻辑(见 v2.1.1 BUG)。
            val last = messages.lastOrNull()
            val rowHeight = rect.height().coerceAtLeast(1)
            val gap = if (last != null) rect.top - last.rect.bottom else Int.MAX_VALUE
            val canMerge = !blockMergeAcrossTime &&
                last != null &&
                last.sender == sender &&
                gap <= rowHeight
            blockMergeAcrossTime = false

            if (canMerge && last != null) {
                val mergedRect = Rect(
                    minOf(last.rect.left, rect.left),
                    last.rect.top,
                    maxOf(last.rect.right, rect.right),
                    rect.bottom,
                )
                messages[messages.size - 1] = last.copy(
                    content = last.content + "\n" + content,
                    rect = mergedRect,
                )
            } else {
                messages.add(OcrMessage(sender, content, currentTime, rect))
            }
        }

        return messages
    }

}
