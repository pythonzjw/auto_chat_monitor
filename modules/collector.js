/**
 * 消息采集模块
 * 
 * 负责在群聊页面中读取消息内容
 * 优先使用控件树读取，失败时降级到 OCR
 */

var config = require("../config.js");
var utils = require("./utils.js");
var storage = require("./storage.js");

var collector = {

    // 当前屏幕上最后一条消息的指纹（用于检测是否有新消息）
    _lastScreenFingerprint: null,

    /**
     * 采集当前屏幕上可见的所有消息
     * 返回消息数组，每条消息包含 {sender, time, content, type, fingerprint}
     * 
     * @returns {object[]} 消息数组
     */
    collectVisibleMessages: function () {
        var messages = [];

        utils.debug("开始采集当前屏幕消息...");
        utils.saveScreenshotIfNeeded("collect");

        // 企业微信聊天页面的消息列表通常是一个 ListView 或 RecyclerView
        // 每条消息是一个包含头像、昵称、内容的容器

        // 策略1：通过消息容器结构采集
        messages = collector._collectByStructure();

        if (messages.length === 0) {
            utils.debug("控件结构采集失败，尝试文本节点采集...");
            // 策略2：直接采集所有文本节点，根据位置关系推断消息
            messages = collector._collectByTextNodes();
        }

        if (messages.length === 0) {
            utils.debug("文本节点采集也失败，尝试 OCR...");
            // 策略3：OCR 降级
            try {
                var ocr = require("./ocr.js");
                messages = ocr.collectByOCR();
            } catch (e) {
                utils.log("OCR 采集失败: " + e);
            }
        }

        // 过滤已转发的消息
        var newMessages = [];
        for (var i = 0; i < messages.length; i++) {
            var msg = messages[i];
            msg.fingerprint = utils.fingerprint(msg.sender, msg.time, msg.content);
            if (!storage.isForwarded(msg.fingerprint)) {
                newMessages.push(msg);
            }
        }

        utils.debug("屏幕可见 " + messages.length + " 条消息，其中 " + newMessages.length + " 条未转发");
        return newMessages;
    },

    /**
     * 策略1：通过消息容器的控件结构采集
     * 
     * 企业微信消息结构大致为：
     * - 时间标签（居中的 TextView，显示时间）
     * - 消息行容器：
     *   - 头像 ImageView
     *   - 发送人 TextView
     *   - 消息内容（文本/图片/文件等）
     */
    _collectByStructure: function () {
        var messages = [];
        var currentTime = ""; // 当前消息组的时间标签

        try {
            // 获取聊天区域内的所有文本节点
            // 企业微信聊天消息的列表容器
            var chatList = className("android.widget.ListView").findOne(2000)
                || className("androidx.recyclerview.widget.RecyclerView").findOne(2000)
                || className("android.widget.AbsListView").findOne(2000);

            if (!chatList) {
                utils.debug("找不到聊天列表容器");
                return messages;
            }

            var childCount = chatList.childCount();
            utils.debug("聊天列表子节点数: " + childCount);

            for (var i = 0; i < childCount; i++) {
                var child = chatList.child(i);
                if (!child) continue;

                var msgInfo = collector._parseMessageNode(child, currentTime);
                if (msgInfo) {
                    if (msgInfo.isTimeLabel) {
                        // 这是一个时间标签节点
                        currentTime = msgInfo.time;
                    } else {
                        // 这是一条消息
                        messages.push(msgInfo);
                    }
                }
            }
        } catch (e) {
            utils.debug("结构化采集异常: " + e);
        }

        return messages;
    },

    /**
     * 解析单个消息节点
     * @param {UiObject} node 消息容器节点
     * @param {string} currentTime 当前时间标签
     * @returns {object|null}
     */
    _parseMessageNode: function (node, currentTime) {
        if (!node) return null;

        try {
            // 获取节点内所有文本
            var texts = [];
            collector._getAllTexts(node, texts);

            if (texts.length === 0) {
                // 可能是图片或文件消息，没有文字
                // 检查是否有图片
                var hasImage = node.findOne(className("ImageView"));
                if (hasImage) {
                    return {
                        sender: "未知",
                        time: currentTime,
                        content: "[图片]",
                        type: "image"
                    };
                }
                return null;
            }

            // 判断是否是时间标签
            // 时间标签通常只有一个文本节点，且内容匹配时间格式
            if (texts.length === 1) {
                var t = texts[0].text;
                if (collector._isTimeLabel(t)) {
                    return { isTimeLabel: true, time: t };
                }
            }

            // 判断是否是系统消息（如 "xxx 加入了群聊"）
            if (texts.length === 1) {
                var t = texts[0].text;
                if (collector._isSystemMessage(t)) {
                    return {
                        sender: "系统",
                        time: currentTime,
                        content: t,
                        type: "system"
                    };
                }
            }

            // 解析发送人和内容
            // 通常第一个文本是发送人昵称，后面的是消息内容
            var sender = "";
            var content = "";
            var type = "text";

            if (texts.length >= 2) {
                // 判断哪个是发送人：通常位置靠上，字号较小
                sender = texts[0].text;
                // 消息内容可能分布在多个文本节点中
                var contentParts = [];
                for (var j = 1; j < texts.length; j++) {
                    contentParts.push(texts[j].text);
                }
                content = contentParts.join(" ");
            } else if (texts.length === 1) {
                content = texts[0].text;
            }

            // 检查是否包含特殊内容标记
            if (content.indexOf("[文件]") >= 0 || node.findOne(desc("文件"))) {
                type = "file";
            } else if (content.indexOf("[链接]") >= 0 || content.indexOf("http") >= 0) {
                type = "link";
            } else if (content === "" && node.findOne(className("ImageView"))) {
                content = "[图片]";
                type = "image";
            }

            if (content === "" && sender === "") return null;

            return {
                sender: sender,
                time: currentTime,
                content: content,
                type: type
            };

        } catch (e) {
            utils.debug("解析消息节点异常: " + e);
            return null;
        }
    },

    /**
     * 递归获取节点下所有文本
     */
    _getAllTexts: function (node, result) {
        if (!node) return;
        var t = node.text();
        if (t && t.trim() !== "") {
            result.push({
                text: t.trim(),
                bounds: node.bounds()
            });
        }
        for (var i = 0; i < node.childCount(); i++) {
            collector._getAllTexts(node.child(i), result);
        }
    },

    /**
     * 策略2：通过所有文本节点推断消息
     * 适用于控件结构不规则的情况
     */
    _collectByTextNodes: function () {
        var messages = [];

        try {
            // 获取屏幕上所有可见的文本节点
            var allTexts = className("android.widget.TextView").find();
            if (!allTexts || allTexts.length === 0) return messages;

            utils.debug("屏幕上文本节点总数: " + allTexts.length);

            // 按 Y 坐标排序
            var textItems = [];
            for (var i = 0; i < allTexts.length; i++) {
                var t = allTexts[i];
                var txt = t.text();
                if (txt && txt.trim() !== "") {
                    var b = t.bounds();
                    textItems.push({
                        text: txt.trim(),
                        x: b.centerX(),
                        y: b.centerY(),
                        width: b.width(),
                        height: b.height(),
                        top: b.top,
                        left: b.left
                    });
                }
            }

            // 按 Y 坐标排序
            textItems.sort(function (a, b) { return a.y - b.y; });

            // 分组：Y 坐标接近的文本节点归为同一行/同一消息
            var currentTime = "";
            var groups = collector._groupByProximity(textItems);

            for (var g = 0; g < groups.length; g++) {
                var group = groups[g];

                // 只有一个元素，可能是时间标签或系统消息
                if (group.length === 1) {
                    if (collector._isTimeLabel(group[0].text)) {
                        currentTime = group[0].text;
                        continue;
                    }
                    if (collector._isSystemMessage(group[0].text)) {
                        messages.push({
                            sender: "系统",
                            time: currentTime,
                            content: group[0].text,
                            type: "system"
                        });
                        continue;
                    }
                }

                // 多个元素，尝试解析为 发送人 + 内容
                if (group.length >= 2) {
                    // 假设第一个是发送人
                    messages.push({
                        sender: group[0].text,
                        time: currentTime,
                        content: group.slice(1).map(function (t) { return t.text; }).join(" "),
                        type: "text"
                    });
                } else if (group.length === 1) {
                    // 单独的文本，可能是消息内容（发送人和内容在同行）
                    messages.push({
                        sender: "未知",
                        time: currentTime,
                        content: group[0].text,
                        type: "text"
                    });
                }
            }

        } catch (e) {
            utils.debug("文本节点采集异常: " + e);
        }

        return messages;
    },

    /**
     * 将文本节点按 Y 坐标临近度分组
     * 同一消息的昵称和内容 Y 坐标较接近
     */
    _groupByProximity: function (items) {
        if (items.length === 0) return [];

        var groups = [];
        var current = [items[0]];

        for (var i = 1; i < items.length; i++) {
            // 如果 Y 坐标差距在 60px 以内，认为是同一组
            if (Math.abs(items[i].y - items[i - 1].y) < 60) {
                current.push(items[i]);
            } else {
                groups.push(current);
                current = [items[i]];
            }
        }
        groups.push(current);

        return groups;
    },

    /**
     * 判断文本是否是时间标签
     */
    _isTimeLabel: function (text) {
        if (!text) return false;
        // 匹配各种时间格式
        return /^\d{1,2}:\d{2}$/.test(text)
            || /^昨天/.test(text)
            || /^星期/.test(text)
            || /^\d{1,2}月\d{1,2}日/.test(text)
            || /^\d{4}[\/-]\d{1,2}[\/-]\d{1,2}/.test(text)
            || /^上午\s*\d/.test(text)
            || /^下午\s*\d/.test(text);
    },

    /**
     * 判断是否是系统消息
     */
    _isSystemMessage: function (text) {
        if (!text) return false;
        return text.indexOf("加入了群聊") >= 0
            || text.indexOf("退出了群聊") >= 0
            || text.indexOf("移出了群聊") >= 0
            || text.indexOf("修改了群名") >= 0
            || text.indexOf("撤回了一条消息") >= 0
            || text.indexOf("你已被") >= 0
            || text.indexOf("邀请了") >= 0;
    },

    /**
     * 向上滚动并采集历史消息
     * 用于启动时回溯 N 分钟内的历史消息
     * 
     * @param {number} lookbackMinutes 回溯分钟数
     * @returns {object[]} 所有采集到的新消息
     */
    collectHistoryMessages: function (lookbackMinutes) {
        utils.log("开始采集历史消息，回溯 " + lookbackMinutes + " 分钟...");

        var allMessages = [];
        var scrollCount = 0;
        var maxScrolls = 50; // 最多翻页次数，防止无限滚动
        var noNewContentCount = 0;
        var lastContent = "";

        while (scrollCount < maxScrolls) {
            // 采集当前屏幕的消息
            var screenMessages = collector.collectVisibleMessages();

            // 检查是否有新内容（通过对比内容判断是否还能继续往上翻）
            var currentContent = screenMessages.map(function (m) { return m.content; }).join("|");
            if (currentContent === lastContent) {
                noNewContentCount++;
                if (noNewContentCount >= 3) {
                    utils.log("连续3次无新内容，停止滚动");
                    break;
                }
            } else {
                noNewContentCount = 0;
                lastContent = currentContent;
            }

            // 检查最早的消息是否已经超出回溯范围
            for (var i = 0; i < screenMessages.length; i++) {
                var msg = screenMessages[i];
                var msgTime = utils.parseMessageTime(msg.time);

                if (msgTime && !utils.isWithinLookback(msgTime, lookbackMinutes)) {
                    utils.log("已到达回溯时间边界，停止滚动");
                    // 只保留范围内的消息
                    for (var j = i; j < screenMessages.length; j++) {
                        var mt = utils.parseMessageTime(screenMessages[j].time);
                        if (!mt || utils.isWithinLookback(mt, lookbackMinutes)) {
                            allMessages.push(screenMessages[j]);
                        }
                    }
                    return collector._deduplicateMessages(allMessages);
                }

                allMessages.push(msg);
            }

            // 向上滚动翻看更早的消息
            utils.swipeUp();
            scrollCount++;
        }

        utils.log("历史消息采集完成，共 " + allMessages.length + " 条（滚动 " + scrollCount + " 次）");
        return collector._deduplicateMessages(allMessages);
    },

    /**
     * 采集新消息（不滚动，只读取当前屏幕底部的新消息）
     * @returns {object[]} 新消息数组
     */
    collectNewMessages: function () {
        var messages = collector.collectVisibleMessages();
        if (messages.length > 0) {
            // 更新最后的屏幕指纹
            var lastMsg = messages[messages.length - 1];
            collector._lastScreenFingerprint = lastMsg.fingerprint;
        }
        return messages;
    },

    /**
     * 检查当前屏幕是否有新消息（和上次采集相比）
     */
    hasNewMessages: function () {
        var allTexts = className("android.widget.TextView").find();
        if (!allTexts || allTexts.length === 0) return false;

        // 取最后一个文本的内容作为简易判断
        var lastText = allTexts[allTexts.length - 1].text();
        var fp = utils.fingerprint("", "", lastText);
        if (fp !== collector._lastScreenFingerprint) {
            return true;
        }
        return false;
    },

    /**
     * 消息去重（根据指纹）
     */
    _deduplicateMessages: function (messages) {
        var seen = {};
        var result = [];
        for (var i = 0; i < messages.length; i++) {
            var fp = messages[i].fingerprint || utils.fingerprint(
                messages[i].sender, messages[i].time, messages[i].content
            );
            if (!seen[fp]) {
                seen[fp] = true;
                messages[i].fingerprint = fp;
                result.push(messages[i]);
            }
        }
        return result;
    }
};

module.exports = collector;
