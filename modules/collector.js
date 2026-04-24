/**
 * 消息采集模块
 * 
 * 负责在群聊页面中读取消息，通过书签机制判断新消息
 * 采集策略：控件树 → 文本节点扫描 → OCR 降级
 */

var config = require("../config.js");
var utils = require("./utils.js");
var storage = require("./storage.js");

var collector = {

    /**
     * 采集当前屏幕上可见的所有消息
     * 返回消息数组，每条 {sender, time, content, type, bounds}
     */
    collectVisibleMessages: function () {
        var messages = [];

        utils.debug("开始采集当前屏幕消息...");
        utils.saveScreenshotIfNeeded("collect");

        // 策略1：通过消息容器结构采集
        messages = collector._collectByStructure();

        if (messages.length === 0) {
            utils.debug("控件结构采集失败，尝试文本节点采集...");
            // 策略2：直接采集所有文本节点
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

        utils.debug("屏幕可见 " + messages.length + " 条消息");
        return messages;
    },

    /**
     * 检查当前屏幕是否有新消息（对比书签）
     * 
     * 逻辑：
     *   1. 读取当前屏幕最后一条消息
     *   2. 和书签对比，如果不同则有新消息
     *   3. 如果没有书签（首次运行），认为所有消息都是新的
     * 
     * @returns {boolean}
     */
    hasNewMessages: function () {
        var bookmark = storage.getBookmark();
        if (!bookmark) {
            utils.debug("没有书签记录，认为有新消息");
            return true;
        }

        // 读取屏幕上最后一条消息
        var lastMsg = collector._getLastVisibleMessage();
        if (!lastMsg) {
            utils.debug("无法读取屏幕消息");
            return false;
        }

        // 和书签对比
        if (storage.matchesBookmark(lastMsg.sender, lastMsg.content)) {
            utils.debug("最后一条消息和书签一致，没有新消息");
            return false;
        }

        utils.debug("发现新消息，最后一条: " + lastMsg.sender + ": " + lastMsg.content);
        return true;
    },

    /**
     * 获取屏幕上最后一条消息（最底部的消息）
     */
    _getLastVisibleMessage: function () {
        var messages = collector.collectVisibleMessages();
        if (messages.length === 0) return null;
        return messages[messages.length - 1];
    },

    /**
     * 找到书签消息在屏幕上的位置
     * 从屏幕底部往上找，返回书签消息对应的控件/坐标
     * 
     * @returns {object|null} {found: true, index: N, bounds: {}} 或 null
     */
    findBookmarkOnScreen: function () {
        var bookmark = storage.getBookmark();
        if (!bookmark) return null;

        var messages = collector.collectVisibleMessages();
        for (var i = messages.length - 1; i >= 0; i--) {
            var msg = messages[i];
            if (storage.matchesBookmark(msg.sender, msg.content)) {
                utils.debug("在屏幕上找到书签消息，位置: " + i);
                return {
                    found: true,
                    index: i,
                    message: msg,
                    totalOnScreen: messages.length
                };
            }
        }
        return null;
    },

    /**
     * 向上滚动查找书签消息
     * 如果当前屏幕看不到书签消息，持续向上滚动直到找到
     * 
     * @param {number} maxScrolls 最大滚动次数
     * @returns {object|null} 同 findBookmarkOnScreen
     */
    scrollUpToBookmark: function (maxScrolls) {
        maxScrolls = maxScrolls || 30;

        for (var i = 0; i < maxScrolls; i++) {
            var result = collector.findBookmarkOnScreen();
            if (result) return result;

            utils.debug("书签不在当前屏幕，继续向上滚动 (" + (i + 1) + "/" + maxScrolls + ")");
            utils.swipeUp();
        }

        utils.log("滚动 " + maxScrolls + " 次仍未找到书签消息");
        return null;
    },

    /**
     * 获取书签之后的第一条新消息的控件位置
     * 用于后续长按该消息进入多选模式
     * 
     * @returns {object|null} 第一条新消息的信息 {content, sender, bounds}
     */
    getFirstNewMessage: function () {
        var bookmark = storage.getBookmark();

        // 没有书签，认为屏幕上最早的消息就是第一条新消息
        if (!bookmark) {
            var messages = collector.collectVisibleMessages();
            if (messages.length > 0) return messages[0];
            return null;
        }

        // 找到书签所在屏幕位置
        var messages = collector.collectVisibleMessages();
        for (var i = 0; i < messages.length; i++) {
            if (storage.matchesBookmark(messages[i].sender, messages[i].content)) {
                // 书签的下一条就是第一条新消息
                if (i + 1 < messages.length) {
                    return messages[i + 1];
                }
                // 书签是屏幕上最后一条，需要往下滚动看有没有更新的
                utils.debug("书签是屏幕最后一条消息，没有新消息");
                return null;
            }
        }

        // 书签不在当前屏幕上，说明已经滚过头了或有很多新消息
        // 这种情况下返回屏幕上第一条消息
        utils.debug("书签不在当前屏幕，返回第一条可见消息作为新消息起点");
        return messages.length > 0 ? messages[0] : null;
    },

    /**
     * 获取当前屏幕上最后一条消息（用于更新书签）
     */
    getLastMessage: function () {
        var messages = collector.collectVisibleMessages();
        if (messages.length === 0) return null;
        return messages[messages.length - 1];
    },

    /**
     * 在屏幕上找到指定消息内容对应的控件
     * 用于长按操作
     */
    findMessageElement: function (msg) {
        if (!msg) return null;

        // 通过消息内容文本查找
        if (msg.content && msg.content !== "[图片]" && msg.content !== "[文件]") {
            var elem = text(msg.content).findOne(1500);
            if (elem) return elem;

            // 部分匹配（消息过长可能截断）
            if (msg.content.length > 20) {
                elem = textContains(msg.content.substring(0, 20)).findOne(1500);
                if (elem) return elem;
            }
        }

        // 通过发送人名字定位
        if (msg.sender && msg.sender !== "未知" && msg.sender !== "系统") {
            var senderElem = text(msg.sender).findOne(1500);
            if (senderElem) {
                var parent = senderElem.parent();
                if (parent) {
                    var siblings = parent.find(className("android.widget.TextView"));
                    for (var i = 0; i < siblings.length; i++) {
                        if (siblings[i].text() !== msg.sender) {
                            return siblings[i];
                        }
                    }
                }
            }
        }

        return null;
    },

    // ==================== 内部采集方法 ====================

    /**
     * 策略1：通过消息容器的控件结构采集
     */
    _collectByStructure: function () {
        var messages = [];
        var currentTime = "";

        try {
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
                        currentTime = msgInfo.time;
                    } else {
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
     */
    _parseMessageNode: function (node, currentTime) {
        if (!node) return null;

        try {
            var texts = [];
            collector._getAllTexts(node, texts);

            if (texts.length === 0) {
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

            // 判断时间标签
            if (texts.length === 1 && collector._isTimeLabel(texts[0].text)) {
                return { isTimeLabel: true, time: texts[0].text };
            }

            // 判断系统消息
            if (texts.length === 1 && collector._isSystemMessage(texts[0].text)) {
                return {
                    sender: "系统",
                    time: currentTime,
                    content: texts[0].text,
                    type: "system"
                };
            }

            // 解析发送人和内容
            var sender = "";
            var content = "";
            var type = "text";

            if (texts.length >= 2) {
                sender = texts[0].text;
                var contentParts = [];
                for (var j = 1; j < texts.length; j++) {
                    contentParts.push(texts[j].text);
                }
                content = contentParts.join(" ");
            } else if (texts.length === 1) {
                content = texts[0].text;
            }

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
     */
    _collectByTextNodes: function () {
        var messages = [];

        try {
            var allTexts = className("android.widget.TextView").find();
            if (!allTexts || allTexts.length === 0) return messages;

            utils.debug("屏幕上文本节点总数: " + allTexts.length);

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

            textItems.sort(function (a, b) { return a.y - b.y; });

            var currentTime = "";
            var groups = collector._groupByProximity(textItems);

            for (var g = 0; g < groups.length; g++) {
                var group = groups[g];

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

                if (group.length >= 2) {
                    messages.push({
                        sender: group[0].text,
                        time: currentTime,
                        content: group.slice(1).map(function (t) { return t.text; }).join(" "),
                        type: "text"
                    });
                } else if (group.length === 1) {
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
     */
    _groupByProximity: function (items) {
        if (items.length === 0) return [];
        var groups = [];
        var current = [items[0]];

        for (var i = 1; i < items.length; i++) {
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
    }
};

module.exports = collector;
