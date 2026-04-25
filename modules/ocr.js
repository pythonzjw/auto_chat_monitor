/**
 * OCR 兜底模块
 * 
 * 当控件树读取失败时，通过截图 + OCR 识别屏幕上的消息
 * 使用 Auto.js 内置的 paddle OCR 或 Google ML Kit
 */

var config = require("../config.js");
var utils = require("./utils.js");

var ocr = {

    // OCR 引擎实例
    _engine: null,

    /**
     * 初始化 OCR 引擎
     * Auto.js Pro 内置了 PaddleOCR
     * 普通版可以用 Tesseract 或 百度 OCR API
     */
    init: function () {
        try {
            // 尝试使用 Auto.js 内置 OCR（4.1+ 版本）
            if (typeof $ocr !== "undefined") {
                ocr._engine = "builtin";
                utils.log("OCR 引擎: Auto.js 内置 OCR");
                return true;
            }

            // 尝试使用 paddle
            if (typeof paddle !== "undefined") {
                ocr._engine = "paddle";
                utils.log("OCR 引擎: PaddleOCR");
                return true;
            }

            // 都不可用，使用 Google ML Kit（需要额外配置）
            utils.log("无可用 OCR 引擎，OCR 兜底功能将不可用");
            utils.log("建议安装 Auto.js Pro 或使用支持 OCR 的版本");
            return false;

        } catch (e) {
            utils.log("OCR 初始化失败: " + e);
            return false;
        }
    },

    /**
     * 通过 OCR 采集当前屏幕消息
     * @returns {object[]} 消息数组
     */
    collectByOCR: function () {
        var messages = [];

        try {
            // 请求截图权限（首次运行需要用户确认）
            if (!images.requestScreenCapture(false)) {
                utils.log("请求截图权限失败");
                return messages;
            }

            utils.wait(500);

            // 截取当前屏幕
            var img = images.captureScreen();
            if (!img) {
                utils.log("截图失败");
                return messages;
            }

            // 裁剪聊天区域（排除顶部标题栏和底部输入框）
            var w = img.getWidth();
            var h = img.getHeight();
            var topCrop = Math.floor(h * 0.08);    // 顶部约8%是标题栏
            var bottomCrop = Math.floor(h * 0.12);  // 底部约12%是输入框
            var chatImg = images.clip(img, 0, topCrop, w, h - topCrop - bottomCrop);

            // 执行 OCR
            var results = ocr._doOCR(chatImg);

            // 回收图片资源
            chatImg.recycle();
            img.recycle();

            if (!results || results.length === 0) {
                utils.debug("OCR 未识别到文本");
                return messages;
            }

            // 解析 OCR 结果为消息格式
            messages = ocr._parseOCRResults(results);

        } catch (e) {
            utils.log("OCR 采集异常: " + e);
        }

        return messages;
    },

    /**
     * 执行 OCR 识别
     * @param {Image} img 要识别的图片
     * @returns {object[]} OCR 结果数组 [{text, bounds}]
     */
    _doOCR: function (img) {
        try {
            if (ocr._engine === "builtin" && typeof $ocr !== "undefined") {
                // Auto.js 内置 OCR
                var results = $ocr.detect(img);
                return results.map(function (r) {
                    return {
                        text: r.text,
                        x: r.bounds ? r.bounds.centerX() : 0,
                        y: r.bounds ? r.bounds.centerY() : 0
                    };
                });
            }

            if (ocr._engine === "paddle" && typeof paddle !== "undefined") {
                var results = paddle.ocr(img);
                return results.map(function (r) {
                    return {
                        text: r.text || r.words,
                        x: r.location ? r.location.left : 0,
                        y: r.location ? r.location.top : 0
                    };
                });
            }

            // 兜底：使用 gmlkit（如果可用）
            if (typeof gmlkit !== "undefined") {
                var results = gmlkit.ocr(img, "zh");
                return results.map(function (r) {
                    return {
                        text: r.text,
                        x: r.bounds ? r.bounds.left : 0,
                        y: r.bounds ? r.bounds.top : 0
                    };
                });
            }

            utils.debug("没有可用的 OCR 引擎");
            return [];

        } catch (e) {
            utils.debug("OCR 执行失败: " + e);
            return [];
        }
    },

    /**
     * 解析 OCR 结果为消息格式
     * 根据文本的 Y 坐标位置，将结果分组为消息
     */
    _parseOCRResults: function (results) {
        if (!results || results.length === 0) return [];

        // 按 Y 坐标排序
        results.sort(function (a, b) { return a.y - b.y; });

        var messages = [];
        var currentTime = "";

        // 按行分组
        var groups = [];
        var currentGroup = [results[0]];

        for (var i = 1; i < results.length; i++) {
            if (Math.abs(results[i].y - results[i - 1].y) < 30) {
                // 同一行
                currentGroup.push(results[i]);
            } else {
                groups.push(currentGroup);
                currentGroup = [results[i]];
            }
        }
        groups.push(currentGroup);

        // 解析每一行
        for (var g = 0; g < groups.length; g++) {
            var line = groups[g];
            var lineText = line.map(function (r) { return r.text; }).join(" ");

            // 判断是否是时间标签
            if (ocr._isTimeText(lineText)) {
                currentTime = lineText;
                continue;
            }

            // 判断是否是系统消息
            if (lineText.indexOf("加入") >= 0 || lineText.indexOf("退出") >= 0
                || lineText.indexOf("撤回") >= 0) {
                messages.push({
                    sender: "系统",
                    time: currentTime,
                    content: lineText,
                    type: "system"
                });
                continue;
            }

            // 尝试解析为 发送人: 内容
            // 企业微信中发送人和内容通常分两行显示
            // 发送人在上，内容在下
            if (line.length >= 1) {
                // 检查下一组是否是消息内容
                if (g + 1 < groups.length) {
                    var nextLine = groups[g + 1];
                    var nextText = nextLine.map(function (r) { return r.text; }).join(" ");

                    // 如果当前行看起来像昵称（较短），下一行像内容
                    if (lineText.length <= 15 && !ocr._isTimeText(nextText)) {
                        messages.push({
                            sender: lineText,
                            time: currentTime,
                            content: nextText,
                            type: "text"
                        });
                        g++; // 跳过下一行
                        continue;
                    }
                }

                // 否则作为独立消息处理
                messages.push({
                    sender: "未知",
                    time: currentTime,
                    content: lineText,
                    type: "text"
                });
            }
        }

        return messages;
    },

    /**
     * 判断文本是否是时间格式
     */
    _isTimeText: function (text) {
        if (!text) return false;
        return new RegExp("^\\d{1,2}:\\d{2}$").test(text)
            || new RegExp("^昨天").test(text)
            || new RegExp("^星期").test(text)
            || new RegExp("^\\d{1,2}月\\d{1,2}日").test(text)
            || new RegExp("^\\d{4}[/\\-]").test(text)
            || new RegExp("^上午").test(text)
            || new RegExp("^下午").test(text);
    }
};

module.exports = ocr;
