/**
 * 页面导航模块
 * 
 * 负责在企业微信中导航：进入群聊、返回消息列表等
 */

var config = require("../config.js");
var utils = require("./utils.js");

var navigator = {

    /**
     * 回到企业微信消息列表首页
     * 通过反复按返回键直到到达消息列表
     */
    goToMessageList: function () {
        utils.log("正在返回消息列表...");
        var maxRetries = 10;
        for (var i = 0; i < maxRetries; i++) {
            // 检查是否已经在消息列表页面
            // 企业微信底部标签栏有"消息"按钮
            var msgTab = text("消息").findOne(1000);
            if (msgTab) {
                // 点击"消息"标签确保在消息列表
                utils.safeClick(msgTab);
                utils.debug("已到达消息列表页面");
                return true;
            }

            // 还没到，按返回键
            utils.goBack();
            utils.wait(500);
        }

        // 兜底：直接启动企业微信
        utils.log("无法返回消息列表，尝试重新启动企业微信");
        app.launchPackage(config.packageName);
        utils.wait(3000);

        var msgTab = text("消息").findOne(3000);
        if (msgTab) {
            utils.safeClick(msgTab);
            return true;
        }

        utils.log("返回消息列表失败！");
        return false;
    },

    /**
     * 从消息列表进入指定群聊
     * @param {string} groupName 群名称
     * @returns {boolean} 是否成功进入
     */
    enterGroup: function (groupName) {
        utils.log("正在进入群聊: " + groupName);

        // 先确保在消息列表
        if (!navigator.goToMessageList()) {
            return false;
        }

        utils.wait(500);

        // 在消息列表中查找群名
        // 先直接找，可能在当前屏幕上
        var group = navigator._findGroupInList(groupName);
        if (group) {
            utils.safeClick(group);
            utils.log("已点击进入群聊: " + groupName);
            // 等待群聊页面加载
            utils.waitSeconds(config.enterGroupWaitSeconds);
            return navigator._verifyInChatPage(groupName);
        }

        // 没找到，尝试搜索
        utils.log("列表中未找到群聊，尝试搜索: " + groupName);
        return navigator._searchAndEnterGroup(groupName);
    },

    /**
     * 在消息列表中查找群（包含向下滚动查找）
     */
    _findGroupInList: function (groupName) {
        // 先在当前屏幕找
        var group = text(groupName).findOne(1500);
        if (group) return group;

        // 向下滑动几次查找
        for (var i = 0; i < 5; i++) {
            utils.swipeDown();
            group = text(groupName).findOne(1000);
            if (group) return group;
        }

        return null;
    },

    /**
     * 通过搜索功能进入群聊
     */
    _searchAndEnterGroup: function (groupName) {
        // 点击搜索框/搜索图标
        // 企业微信顶部一般有搜索区域
        var searchBox = id("ici").findOne(2000)    // 搜索框常见 id
            || desc("搜索").findOne(2000)
            || text("搜索").findOne(2000);

        if (!searchBox) {
            utils.log("找不到搜索入口");
            return false;
        }

        utils.safeClick(searchBox);
        utils.wait(1000);

        // 在搜索框中输入群名
        var input = className("EditText").findOne(3000);
        if (!input) {
            utils.log("找不到搜索输入框");
            utils.goBack();
            return false;
        }

        input.setText(groupName);
        utils.wait(1500); // 等待搜索结果

        // 查找搜索结果中的群名
        var result = text(groupName).findOne(3000);
        if (!result) {
            utils.log("搜索结果中未找到群聊: " + groupName);
            utils.goBack();
            utils.goBack();
            return false;
        }

        utils.safeClick(result);
        utils.waitSeconds(config.enterGroupWaitSeconds);
        return navigator._verifyInChatPage(groupName);
    },

    /**
     * 验证是否成功进入了聊天页面
     */
    _verifyInChatPage: function (groupName) {
        // 聊天页面一般顶部有群名，底部有输入框
        var titleCheck = text(groupName).findOne(2000);
        var inputCheck = className("EditText").findOne(2000)
            || id("bhn").findOne(2000);  // 企业微信输入框 id

        if (titleCheck || inputCheck) {
            utils.debug("已确认进入群聊页面: " + groupName);
            return true;
        }

        utils.log("进入群聊验证失败: " + groupName);
        return false;
    },

    /**
     * 从群聊页面返回消息列表
     */
    exitGroup: function () {
        utils.debug("退出当前群聊...");
        utils.goBack();
        utils.wait(500);

        // 确认回到消息列表
        var msgTab = text("消息").findOne(2000);
        if (msgTab) {
            utils.debug("已返回消息列表");
            return true;
        }

        // 可能还在某个中间页面，再按一次返回
        utils.goBack();
        utils.wait(500);
        return !!text("消息").findOne(2000);
    },

    /**
     * 检查指定群是否有新消息未读
     * 在消息列表页面检查群名旁是否有未读标记
     * @param {string} groupName 群名称
     * @returns {boolean}
     */
    hasUnreadMessages: function (groupName) {
        var groupItem = text(groupName).findOne(1500);
        if (!groupItem) return false;

        // 检查群名所在行是否有未读角标
        // 企业微信的未读标记通常在群名同级或父容器中
        try {
            var parent = groupItem.parent();
            if (parent) {
                // 查找未读数字角标
                var badge = parent.findOne(className("TextView").textMatches("^\\d+$"));
                if (badge) {
                    utils.debug(groupName + " 有 " + badge.text() + " 条未读消息");
                    return true;
                }
                // 有些时候只显示红点，没有数字
                var redDot = parent.findOne(className("ImageView"));
                // 这里无法精确判断红点，简化处理
            }
        } catch (e) {
            utils.debug("检查未读消息失败: " + e);
        }

        return false;
    }
};

module.exports = navigator;
