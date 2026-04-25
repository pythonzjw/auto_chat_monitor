/**
 * 消息转发模块
 * 
 * 核心流程：
 *   1. 长按第一条新消息 → 点"多选"
 *   2. 滚动到最后一条新消息 → 点"选择到这里"（一次性全选）
 *   3. 点击"转发"
 *   4. 在选群页面逐个搜索目标群并勾选（最多9个）
 *   5. 点击"发送"，一次性发送到所有勾选的群
 */

var config = require("../config.js");
var utils = require("./utils.js");
var storage = require("./storage.js");
var collector = require("./collector.js");

var forwarder = {

    /**
     * 执行完整的转发流程
     * 
     * 前提：当前已在源群聊天页面，且已确认有新消息
     * 
     * @returns {boolean} 是否转发成功
     */
    forwardNewMessages: function () {
        utils.log("开始执行转发流程...");

        // 步骤1：先滚动到底部，确保能看到最新消息
        utils.log("滚动到最新消息...");
        forwarder._scrollToBottom();
        utils.wait(500);

        // 记录当前屏幕最后一条消息（转发完成后更新书签用）
        var lastMsg = collector.getLastMessage();

        // 步骤2：找到第一条新消息并长按
        var bookmark = storage.getBookmark();
        var firstNewMsg = null;

        if (bookmark) {
            // 有书签，需要滚动到书签位置，然后从书签下一条开始
            utils.log("查找书签位置...");
            // 先往上滚动找书签
            var bookmarkInfo = collector.scrollUpToBookmark(30);

            if (bookmarkInfo) {
                utils.log("找到书签，获取第一条新消息...");
                // 书签在屏幕上，往下看有没有新消息
                firstNewMsg = collector.getFirstNewMessage();

                if (!firstNewMsg) {
                    // 书签是最后一条，需要往下滚动
                    utils.swipeDown();
                    firstNewMsg = collector.getFirstNewMessage();
                }
            } else {
                // 书签找不到了（可能消息太多已经翻过去了），从当前屏幕第一条开始
                utils.log("书签未找到，从当前可见的最早消息开始");
                var visibleMsgs = collector.collectVisibleMessages();
                if (visibleMsgs.length > 0) {
                    firstNewMsg = visibleMsgs[0];
                }
            }
        } else {
            // 没有书签（首次运行），回溯 N 分钟
            utils.log("首次运行，回溯 " + config.lookbackMinutes + " 分钟的消息...");
            forwarder._scrollUpForMinutes(config.lookbackMinutes);
            var visibleMsgs = collector.collectVisibleMessages();
            if (visibleMsgs.length > 0) {
                firstNewMsg = visibleMsgs[0];
            }
        }

        if (!firstNewMsg) {
            utils.log("没有找到新消息，跳过转发");
            return false;
        }

        // 步骤3：转发前先保存书签（指向最新消息）
        // 这样即使后续转发过程中崩溃，重启后书签已经指向最新位置，不会重复转发
        if (lastMsg) {
            storage.saveBookmark(lastMsg.sender, lastMsg.content, lastMsg.time);
            utils.log("书签已提前更新: " + (lastMsg.sender || "") + ": " + (lastMsg.content || "").substring(0, 20));
        }

        // 步骤4：长按第一条新消息
        utils.log("长按第一条新消息: " + (firstNewMsg.sender || "") + ": " + (firstNewMsg.content || "").substring(0, 20));
        var msgElem = collector.findMessageElement(firstNewMsg);
        if (!msgElem) {
            utils.log("找不到第一条新消息的控件，无法长按");
            return false;
        }

        var b = msgElem.bounds();
        utils.safeLongPress(b.centerX(), b.centerY());

        // 步骤5：点击"多选"
        var multiSelectBtn = text("多选").findOne(3000) || desc("多选").findOne(2000);
        if (!multiSelectBtn) {
            utils.log("找不到'多选'按钮");
            forwarder._dismissPopup();
            return false;
        }
        utils.safeClick(multiSelectBtn);
        utils.wait(800);

        // 步骤6：滚动到最底部，然后点"选择到这里"全选新消息
        utils.log("滚动到最新消息并全选...");
        var selectOk = forwarder._scrollAndSelectToHere();
        if (!selectOk) {
            utils.log("全选消息失败");
            forwarder._exitMultiSelect();
            return false;
        }

        // 步骤7：点击"转发"
        utils.log("点击转发...");
        var forwardOk = forwarder._clickForward();
        if (!forwardOk) {
            utils.log("点击转发失败");
            forwarder._exitMultiSelect();
            return false;
        }

        // 步骤8：在选群页面勾选所有目标群
        utils.log("选择目标群...");
        var selectGroupOk = forwarder._selectTargetGroups();
        if (!selectGroupOk) {
            utils.log("选择目标群失败");
            utils.goBack();
            forwarder._exitMultiSelect();
            return false;
        }

        // 步骤9：点击发送
        utils.log("确认发送...");
        var sendOk = forwarder._confirmSend();
        if (!sendOk) {
            utils.log("确认发送失败");
            return false;
        }

        // 书签已在步骤3提前保存，这里不需要再保存
        // 保存消息记录到本地 JSON
        storage.saveMessages([firstNewMsg]);

        utils.log("转发完成！");
        return true;
    },

    /**
     * 滚动到聊天底部（最新消息位置）
     */
    _scrollToBottom: function () {
        for (var i = 0; i < 5; i++) {
            utils.swipeDown();
        }
    },

    /**
     * 向上滚动指定分钟数的消息
     */
    _scrollUpForMinutes: function (minutes) {
        var maxScrolls = 50;
        for (var i = 0; i < maxScrolls; i++) {
            var messages = collector.collectVisibleMessages();
            // 检查最早的消息是否已超出回溯范围
            for (var j = 0; j < messages.length; j++) {
                var msgTime = utils.parseMessageTime(messages[j].time);
                if (msgTime && !utils.isWithinLookback(msgTime, minutes)) {
                    utils.log("已到达回溯时间边界");
                    return;
                }
            }
            utils.swipeUp();
        }
    },

    /**
     * 在多选模式下，滚动到底部并点击"选择到这里"
     * 
     * 企业微信多选模式下，滑到某个位置后会出现"选择到这里"的按钮，
     * 点击后会自动选中从长按那条到当前位置之间的所有消息
     */
    _scrollAndSelectToHere: function () {
        // 先滚动到最底部
        for (var i = 0; i < 10; i++) {
            utils.swipeDown();

            // 每次滚动后检查是否出现了"选择到这里"
            var selectBtn = text("选择到这里").findOne(1000)
                || textContains("选择到这里").findOne(800)
                || desc("选择到这里").findOne(800);

            if (selectBtn) {
                utils.log("找到'选择到这里'按钮");
                utils.safeClick(selectBtn);
                utils.wait(1000);
                return true;
            }
        }

        // 如果滚动后没有出现"选择到这里"，可能消息很少不需要滚动
        // 再找一次
        var selectBtn = text("选择到这里").findOne(2000)
            || textContains("选择到这里").findOne(1500)
            || desc("选择到这里").findOne(1500);

        if (selectBtn) {
            utils.safeClick(selectBtn);
            utils.wait(1000);
            return true;
        }

        // 可能只有一条消息，不需要"选择到这里"，长按时已经选中了
        utils.log("未找到'选择到这里'按钮，可能只有一条新消息（已在长按时选中）");
        return true;
    },

    /**
     * 点击转发按钮
     */
    _clickForward: function () {
        // 多选模式下底部有转发按钮
        var forwardBtn = text("转发").findOne(3000)
            || desc("转发").findOne(2000);

        if (!forwardBtn) {
            utils.log("找不到'转发'按钮");
            return false;
        }

        utils.safeClick(forwardBtn);
        utils.wait(1000);

        // 可能弹出"逐条转发"/"合并转发"选项
        var oneByOne = text("逐条转发").findOne(2000);
        if (oneByOne) {
            utils.safeClick(oneByOne);
            utils.wait(800);
        }

        return true;
    },

    /**
     * 在转发选群页面，逐个搜索并勾选目标群
     * 
     * 流程：
     *   对每个目标群：
     *     1. 在搜索框输入群名
     *     2. 等待搜索结果
     *     3. 勾选目标群
     *     4. 清空搜索框
     *   最后所有群都勾选完毕
     */
    _selectTargetGroups: function () {
        var selectedCount = 0;

        for (var i = 0; i < config.targetGroups.length; i++) {
            var groupName = config.targetGroups[i];
            utils.log("搜索目标群 (" + (i + 1) + "/" + config.targetGroups.length + "): " + groupName);

            // 找到搜索输入框
            var searchInput = className("EditText").findOne(3000);
            if (!searchInput) {
                utils.log("找不到搜索输入框");
                continue;
            }

            // 清空并输入群名
            searchInput.setText(groupName);
            utils.wait(config.searchWaitDelay);

            // 在搜索结果中找到并勾选目标群
            var result = text(groupName).findOne(3000);
            if (result) {
                utils.safeClick(result);
                selectedCount++;
                utils.debug("已勾选: " + groupName);
            } else {
                utils.log("搜索结果中未找到: " + groupName);
            }

            // 清空搜索框，准备搜索下一个
            searchInput.setText("");
            utils.wait(500);
        }

        utils.log("共勾选 " + selectedCount + "/" + config.targetGroups.length + " 个目标群");
        return selectedCount > 0;
    },

    /**
     * 点击确认发送
     */
    _confirmSend: function () {
        // 勾选完群后，点击"发送"或"确定"按钮
        // 企业微信可能显示"发送(N)"格式，N 是群的数量
        var sendBtn = textMatches("发送\\s*\\(\\d+\\)").findOne(3000)
            || text("发送").findOne(2000)
            || text("确定").findOne(2000)
            || text("确认").findOne(2000);

        if (!sendBtn) {
            utils.log("找不到发送按钮");
            return false;
        }

        utils.safeClick(sendBtn);
        utils.wait(1500);

        // 可能有二次确认弹窗
        var confirmAgain = text("发送").findOne(1500)
            || text("确定").findOne(1500);
        if (confirmAgain) {
            utils.safeClick(confirmAgain);
            utils.wait(1000);
        }

        return true;
    },

    /**
     * 退出多选模式
     */
    _exitMultiSelect: function () {
        var cancelBtn = text("取消").findOne(2000)
            || desc("取消").findOne(2000)
            || desc("关闭").findOne(2000);

        if (cancelBtn) {
            utils.safeClick(cancelBtn);
        } else {
            utils.goBack();
        }
        utils.wait(500);
    },

    /**
     * 关闭弹出菜单（长按后没点到多选的情况）
     */
    _dismissPopup: function () {
        // 点击屏幕空白区域关闭弹出菜单
        click(device.width / 2, device.height * 0.1);
        utils.wait(500);
    }
};

module.exports = forwarder;
