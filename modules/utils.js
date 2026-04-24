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
        var hash = 0;
        for (var i = 0; i < key.length; i++) {
            var ch = key.charCodeAt(i);
            hash = ((hash << 5) - hash) + ch;
            hash = hash & hash;
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
     * 返回 Date 对象，解析失败返回 null
     */
    parseMessageTime: function (timeText) {
        if (!timeText) return null;
        timeText = timeText.trim();

        var now = new Date();
        var today = new Date(now.getFullYear(), now.getMonth(), now.getDate());

        // 格式: "HH:mm"
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
     */
    isWithinLookback: function (msgTime, lookbackMinutes) {
        if (!msgTime) return true;
        var cutoff = new Date(Date.now() - lookbackMinutes * 60 * 1000);
        return msgTime.getTime() >= cutoff.getTime();
    },

    /**
     * 日志输出
     */
    log: function (msg) {
        var line = "[" + utils.now() + "] " + msg;
        console.log(line);
        try {
            var logPath = config.dataDir + config.logFile;
            files.append(logPath, line + "\n");
        } catch (e) {
            // 忽略
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
     * 生成随机整数 [min, max]
     */
    randomInt: function (min, max) {
        return Math.floor(Math.random() * (max - min + 1)) + min;
    },

    /**
     * 等待指定毫秒（加随机偏移，模拟真人）
     */
    wait: function (ms) {
        var extra = utils.randomInt(0, config.randomDelayMax);
        sleep(ms + extra);
    },

    /**
     * 等待指定秒（加随机偏移）
     */
    waitSeconds: function (seconds) {
        var extra = utils.randomInt(0, config.randomDelayMax);
        sleep(seconds * 1000 + extra);
    },

    /**
     * 精确等待，不加随机偏移
     */
    waitExact: function (ms) {
        sleep(ms);
    },

    /**
     * 给坐标加随机偏移，模拟真人点击位置不精确
     */
    offsetXY: function (x, y) {
        var ox = utils.randomInt(-config.clickOffsetMax, config.clickOffsetMax);
        var oy = utils.randomInt(-config.clickOffsetMax, config.clickOffsetMax);
        return { x: x + ox, y: y + oy };
    },

    /**
     * 安全点击控件，带重试和随机偏移
     */
    safeClick: function (obj, retries) {
        retries = retries || 3;
        for (var i = 0; i < retries; i++) {
            try {
                if (obj && obj.exists()) {
                    var b = obj.bounds();
                    var pos = utils.offsetXY(b.centerX(), b.centerY());
                    click(pos.x, pos.y);
                    utils.wait(config.clickDelay);
                    return true;
                }
            } catch (e) {
                utils.debug("点击失败，重试 " + (i + 1) + "/" + retries);
            }
            utils.waitExact(500);
        }
        return false;
    },

    /**
     * 安全点击坐标（带随机偏移）
     */
    safeClickXY: function (x, y) {
        var pos = utils.offsetXY(x, y);
        click(pos.x, pos.y);
        utils.wait(config.clickDelay);
    },

    /**
     * 长按坐标（带随机偏移）
     */
    safeLongPress: function (x, y) {
        var pos = utils.offsetXY(x, y);
        press(pos.x, pos.y, config.longPressDuration + utils.randomInt(0, 200));
        utils.wait(config.clickDelay);
    },

    /**
     * 向上滑动（模拟手指上划翻看历史消息，带随机偏移）
     */
    swipeUp: function () {
        var w = device.width;
        var h = device.height;
        var startX = w / 2 + utils.randomInt(-30, 30);
        var startY = h * 0.3 + utils.randomInt(-20, 20);
        var endX = w / 2 + utils.randomInt(-30, 30);
        var endY = h * 0.7 + utils.randomInt(-20, 20);
        var duration = 300 + utils.randomInt(0, 200);
        swipe(startX, startY, endX, endY, duration);
        utils.wait(config.swipeDelay);
    },

    /**
     * 向下滑动（带随机偏移）
     */
    swipeDown: function () {
        var w = device.width;
        var h = device.height;
        var startX = w / 2 + utils.randomInt(-30, 30);
        var startY = h * 0.7 + utils.randomInt(-20, 20);
        var endX = w / 2 + utils.randomInt(-30, 30);
        var endY = h * 0.3 + utils.randomInt(-20, 20);
        var duration = 300 + utils.randomInt(0, 200);
        swipe(startX, startY, endX, endY, duration);
        utils.wait(config.swipeDelay);
    },

    /**
     * 等待控件出现
     */
    waitForSelector: function (selectorFn, timeout) {
        timeout = timeout || config.pageLoadTimeout;
        var end = Date.now() + timeout;
        while (Date.now() < end) {
            var obj = selectorFn();
            if (obj && obj.exists()) {
                return obj;
            }
            utils.waitExact(300);
        }
        return null;
    },

    /**
     * 检查当前是否在企业微信中
     */
    isInWeWork: function () {
        return currentPackage() === config.packageName;
    },

    /**
     * 确保企业微信在前台
     */
    ensureWeWorkForeground: function () {
        if (!utils.isInWeWork()) {
            utils.log("企业微信不在前台，正在启动...");
            app.launchPackage(config.packageName);
            utils.waitExact(3000);
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
