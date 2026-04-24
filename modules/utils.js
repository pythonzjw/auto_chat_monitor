/**
 * 工具函数模块
 */

var config = require("../config.js");

var utils = {
    /**
     * 生成消息指纹（用于去重）
     * 用 发送人 + 时间 + 内容前30字 拼接
     */
    fingerprint: function (sender, time, content) {
        var key = (sender || "") + "|" + (time || "") + "|" + (content || "").substring(0, 30);
        // 简单哈希
        var hash = 0;
        for (var i = 0; i < key.length; i++) {
            var ch = key.charCodeAt(i);
            hash = ((hash << 5) - hash) + ch;
            hash = hash & hash; // 转为32位整数
        }
        return hash.toString(36);
    },

    /**
     * 获取当前时间字符串 yyyy-MM-dd HH:mm:ss
     */
    now: function () {
        var d = new Date();
        var pad = function (n) { return n < 10 ? "0" + n : "" + n; };
        return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()) +
            " " + pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
    },

    /**
     * 解析企业微信消息中的时间文本
     * 企业微信显示的时间格式可能有：
     *   - "10:30"（今天的消息）
     *   - "昨天 10:30"
     *   - "星期一 10:30"
     *   - "2026/04/20 10:30"
     *   - "4月20日 10:30"
     * 
     * 返回 Date 对象，解析失败返回 null
     */
    parseMessageTime: function (timeText) {
        if (!timeText) return null;
        timeText = timeText.trim();

        var now = new Date();
        var today = new Date(now.getFullYear(), now.getMonth(), now.getDate());

        // 格式: "HH:mm" — 今天
        var m = timeText.match(/^(\d{1,2}):(\d{2})$/);
        if (m) {
            return new Date(today.getFullYear(), today.getMonth(), today.getDate(),
                parseInt(m[1]), parseInt(m[2]));
        }

        // 格式: "昨天 HH:mm"
        m = timeText.match(/^昨天\s*(\d{1,2}):(\d{2})$/);
        if (m) {
            var yesterday = new Date(today.getTime() - 86400000);
            return new Date(yesterday.getFullYear(), yesterday.getMonth(), yesterday.getDate(),
                parseInt(m[1]), parseInt(m[2]));
        }

        // 格式: "星期X HH:mm"
        m = timeText.match(/^星期[一二三四五六日天]\s*(\d{1,2}):(\d{2})$/);
        if (m) {
            // 粗略处理，当作本周内的消息
            return new Date(today.getFullYear(), today.getMonth(), today.getDate(),
                parseInt(m[1]), parseInt(m[2]));
        }

        // 格式: "M月D日 HH:mm"
        m = timeText.match(/^(\d{1,2})月(\d{1,2})日\s*(\d{1,2}):(\d{2})$/);
        if (m) {
            return new Date(today.getFullYear(), parseInt(m[1]) - 1, parseInt(m[2]),
                parseInt(m[3]), parseInt(m[4]));
        }

        // 格式: "yyyy/MM/dd HH:mm" 或 "yyyy-MM-dd HH:mm"
        m = timeText.match(/^(\d{4})[\/\-](\d{1,2})[\/\-](\d{1,2})\s*(\d{1,2}):(\d{2})$/);
        if (m) {
            return new Date(parseInt(m[1]), parseInt(m[2]) - 1, parseInt(m[3]),
                parseInt(m[4]), parseInt(m[5]));
        }

        return null;
    },

    /**
     * 判断时间是否在回溯范围内
     * @param {Date} msgTime 消息时间
     * @param {number} lookbackMinutes 回溯分钟数
     * @returns {boolean}
     */
    isWithinLookback: function (msgTime, lookbackMinutes) {
        if (!msgTime) return true; // 解析不了的默认采集
        var cutoff = new Date(Date.now() - lookbackMinutes * 60 * 1000);
        return msgTime.getTime() >= cutoff.getTime();
    },

    /**
     * 日志输出
     */
    log: function (msg) {
        var line = "[" + utils.now() + "] " + msg;
        console.log(line);
        // 同时写入日志文件
        try {
            var logPath = config.dataDir + config.logFile;
            files.append(logPath, line + "\n");
        } catch (e) {
            // 忽略写日志失败
        }
    },

    /**
     * 调试日志（仅 debug 模式输出）
     */
    debug: function (msg) {
        if (config.debug) {
            utils.log("[DEBUG] " + msg);
        }
    },

    /**
     * 等待指定毫秒
     */
    wait: function (ms) {
        sleep(ms);
    },

    /**
     * 等待指定秒
     */
    waitSeconds: function (seconds) {
        sleep(seconds * 1000);
    },

    /**
     * 安全点击控件，带重试
     * @param {UiObject} obj 控件对象
     * @param {number} retries 重试次数
     * @returns {boolean} 是否点击成功
     */
    safeClick: function (obj, retries) {
        retries = retries || 3;
        for (var i = 0; i < retries; i++) {
            try {
                if (obj && obj.exists()) {
                    obj.click();
                    utils.wait(config.clickDelay);
                    return true;
                }
            } catch (e) {
                utils.debug("点击失败，重试 " + (i + 1) + "/" + retries);
            }
            utils.wait(500);
        }
        return false;
    },

    /**
     * 安全点击坐标
     */
    safeClickXY: function (x, y) {
        click(x, y);
        utils.wait(config.clickDelay);
    },

    /**
     * 向上滑动（模拟手指上划翻看历史消息）
     */
    swipeUp: function () {
        var w = device.width;
        var h = device.height;
        swipe(w / 2, h * 0.3, w / 2, h * 0.7, 300);
        utils.wait(config.swipeDelay);
    },

    /**
     * 向下滑动
     */
    swipeDown: function () {
        var w = device.width;
        var h = device.height;
        swipe(w / 2, h * 0.7, w / 2, h * 0.3, 300);
        utils.wait(config.swipeDelay);
    },

    /**
     * 等待控件出现
     * @param {function} selectorFn 返回 UiSelector 的函数
     * @param {number} timeout 超时毫秒数
     * @returns {UiObject|null}
     */
    waitForSelector: function (selectorFn, timeout) {
        timeout = timeout || config.pageLoadTimeout;
        var end = Date.now() + timeout;
        while (Date.now() < end) {
            var obj = selectorFn();
            if (obj && obj.exists()) {
                return obj;
            }
            utils.wait(300);
        }
        return null;
    },

    /**
     * 检查当前是否在企业微信中
     */
    isInWeWork: function () {
        var pkg = currentPackage();
        return pkg === config.packageName;
    },

    /**
     * 确保企业微信在前台
     */
    ensureWeWorkForeground: function () {
        if (!utils.isInWeWork()) {
            utils.log("企业微信不在前台，正在启动...");
            app.launchPackage(config.packageName);
            utils.wait(3000);
            if (!utils.isInWeWork()) {
                utils.log("启动企业微信失败！");
                return false;
            }
        }
        return true;
    },

    /**
     * 按返回键
     */
    goBack: function () {
        back();
        utils.wait(config.clickDelay);
    },

    /**
     * 截图保存（调试用）
     */
    saveScreenshotIfNeeded: function (tag) {
        if (!config.saveScreenshot) return;
        try {
            var img = captureScreen();
            var path = config.dataDir + "screenshots/" + tag + "_" + Date.now() + ".png";
            files.ensureDir(path);
            images.save(img, path);
            img.recycle();
            utils.debug("截图已保存: " + path);
        } catch (e) {
            utils.debug("截图保存失败: " + e);
        }
    }
};

module.exports = utils;
