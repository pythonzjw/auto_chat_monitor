/**
 * 企业微信群消息采集转发脚本 - 主入口
 * 
 * 功能：
 *   1. 持续监控指定源群的消息
 *   2. 启动时先回溯 N 分钟的历史消息并转发
 *   3. 之后持续轮询，有新消息就一次全选转发到所有目标群（最多9个）
 *   4. 消息本地备份到 JSON 文件
 * 
 * 运行环境：AutoJs6 APK
 * 前提条件：
 *   - 已授予无障碍服务权限
 *   - 已授予悬浮窗权限
 *   - 企业微信已登录
 *   - 手机屏幕保持常亮
 */

"auto";

// ==================== 加载模块 ====================
var config = require("./config.js");
var utils = require("./modules/utils.js");
var storage = require("./modules/storage.js");
var navigator = require("./modules/navigator.js");
var collector = require("./modules/collector.js");
var forwarder = require("./modules/forwarder.js");

// ==================== 配置持久化 ====================

var CONFIG_PATH = config.dataDir + "user_config.json";

/**
 * 加载用户保存的配置
 */
function loadUserConfig() {
    try {
        if (files.exists(CONFIG_PATH)) {
            var data = JSON.parse(files.read(CONFIG_PATH));
            if (data.sourceGroup) config.sourceGroup = data.sourceGroup;
            if (data.targetGroups) config.targetGroups = data.targetGroups;
            if (data.lookbackMinutes) config.lookbackMinutes = data.lookbackMinutes;
            if (data.enterGroupWaitSeconds) config.enterGroupWaitSeconds = data.enterGroupWaitSeconds;
            if (data.pollIntervalSeconds) config.pollIntervalSeconds = data.pollIntervalSeconds;
            return true;
        }
    } catch (e) {
        // 忽略，使用默认配置
    }
    return false;
}

/**
 * 保存用户配置到本地
 */
function saveUserConfig() {
    try {
        files.ensureDir(config.dataDir);
        files.write(CONFIG_PATH, JSON.stringify({
            sourceGroup: config.sourceGroup,
            targetGroups: config.targetGroups,
            lookbackMinutes: config.lookbackMinutes,
            enterGroupWaitSeconds: config.enterGroupWaitSeconds,
            pollIntervalSeconds: config.pollIntervalSeconds
        }, null, 2));
    } catch (e) {
        // 忽略
    }
}

// ==================== 配置对话框 ====================

/**
 * 弹窗让用户输入配置
 * @returns {boolean} 用户是否确认
 */
function showConfigDialog() {
    var source = dialogs.rawInput("源群名称", config.sourceGroup || "");
    if (source === null) return false;
    source = source.trim();
    if (!source) {
        toast("源群名称不能为空");
        return false;
    }

    var targetStr = dialogs.rawInput("目标群名称（多个群用逗号分隔，最多9个）",
        config.targetGroups.join(","));
    if (targetStr === null) return false;
    var targets = targetStr.split(new RegExp("[,，]"))
        .map(function (s) { return s.trim(); })
        .filter(function (s) { return s !== ""; });
    if (targets.length === 0) {
        toast("请填写至少一个目标群");
        return false;
    }
    if (targets.length > 9) {
        toast("目标群最多9个");
        return false;
    }

    var lookback = dialogs.rawInput("启动时回溯分钟数", String(config.lookbackMinutes));
    if (lookback === null) return false;

    var pollInterval = dialogs.rawInput("轮询间隔（秒）", String(config.pollIntervalSeconds));
    if (pollInterval === null) return false;

    // 保存到 config
    config.sourceGroup = source;
    config.targetGroups = targets;
    config.lookbackMinutes = parseInt(lookback) || 10;
    config.pollIntervalSeconds = parseInt(pollInterval) || 30;

    // 持久化
    saveUserConfig();

    return true;
}

// ==================== 悬浮窗 ====================

var running = false;
var mainThread = null;
var logLines = [];
var MAX_LOG_LINES = 50;

/**
 * 追加日志到悬浮窗
 */
function appendLog(msg) {
    var line = "[" + utils.now().substring(11) + "] " + msg;
    logLines.push(line);
    if (logLines.length > MAX_LOG_LINES) {
        logLines = logLines.slice(logLines.length - MAX_LOG_LINES);
    }
    updateFloatyLog();
}

/**
 * 更新悬浮窗日志显示
 */
function updateFloatyLog() {
    try {
        if (window) {
            ui.run(function () {
                window.logText.setText(logLines.join("\n"));
            });
        }
    } catch (e) {
        // 悬浮窗可能已关闭
    }
}

// 拦截日志输出到悬浮窗
var originalLog = utils.log;
utils.log = function (msg) {
    originalLog.call(utils, msg);
    appendLog(msg);
};

// 创建悬浮窗
var window = floaty.rawWindow(
    '<frame gravity="center" bg="#cc333333">' +
    '  <vertical padding="8">' +
    '    <text id="title" text="企微消息采集" textSize="14sp" textColor="#ffffff" gravity="center" />' +
    '    <text id="status" text="未运行" textSize="12sp" textColor="#aaaaaa" gravity="center" marginTop="4" />' +
    '    <scroll w="280" h="200" marginTop="4">' +
    '      <text id="logText" text="" textSize="10sp" textColor="#cccccc" />' +
    '    </scroll>' +
    '    <horizontal gravity="center" marginTop="4">' +
    '      <text id="btnStart" text="[启动]" textSize="14sp" textColor="#4CAF50" padding="8" />' +
    '      <text id="btnStop" text="[停止]" textSize="14sp" textColor="#f44336" padding="8" />' +
    '      <text id="btnConfig" text="[设置]" textSize="14sp" textColor="#2196F3" padding="8" />' +
    '      <text id="btnHide" text="[收起]" textSize="14sp" textColor="#FF9800" padding="8" />' +
    '    </horizontal>' +
    '  </vertical>' +
    '</frame>'
);

// 悬浮窗位置
window.setPosition(50, 200);
window.setTouchable(true);

// 小窗模式（收起后只显示一个小按钮）
var collapsed = false;

// 拖动支持
var downX = 0, downY = 0;
var windowX = 0, windowY = 0;
var moved = false;

window.title.setOnTouchListener(function (view, event) {
    var action = event.getAction();
    if (action === event.ACTION_DOWN) {
        downX = event.getRawX();
        downY = event.getRawY();
        windowX = window.getX();
        windowY = window.getY();
        moved = false;
        return true;
    }
    if (action === event.ACTION_MOVE) {
        var dx = event.getRawX() - downX;
        var dy = event.getRawY() - downY;
        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
            moved = true;
            window.setPosition(windowX + dx, windowY + dy);
        }
        return true;
    }
    return false;
});

// ==================== 按钮事件 ====================

window.btnStart.on("click", function () {
    if (running) {
        toast("已在运行中");
        return;
    }

    // 如果没有配置过，先弹窗配置
    if (!config.sourceGroup || config.sourceGroup === "测试源群") {
        threads.start(function () {
            if (!showConfigDialog()) {
                toast("配置取消");
                return;
            }
            startCollector();
        });
    } else {
        startCollector();
    }
});

window.btnStop.on("click", function () {
    if (!running) {
        toast("当前没有运行中的任务");
        return;
    }
    stopCollector();
});

window.btnConfig.on("click", function () {
    if (running) {
        toast("请先停止采集再修改配置");
        return;
    }
    threads.start(function () {
        showConfigDialog();
    });
});

window.btnHide.on("click", function () {
    if (collapsed) {
        // 展开
        ui.run(function () {
            window.logText.setVisibility(0); // VISIBLE
            window.btnStart.setVisibility(0);
            window.btnStop.setVisibility(0);
            window.btnConfig.setVisibility(0);
            window.status.setVisibility(0);
            window.btnHide.setText("[收起]");
        });
        collapsed = false;
    } else {
        // 收起
        ui.run(function () {
            window.logText.setVisibility(8); // GONE
            window.btnStart.setVisibility(8);
            window.btnStop.setVisibility(8);
            window.btnConfig.setVisibility(8);
            window.status.setVisibility(8);
            window.btnHide.setText("[展开]");
        });
        collapsed = true;
    }
});

// ==================== 启动/停止 ====================

function startCollector() {
    running = true;
    ui.run(function () {
        window.status.setText("运行中");
        window.status.setTextColor(colors.parseColor("#4CAF50"));
    });
    toast("采集任务已启动");

    mainThread = threads.start(function () {
        try {
            runCollector();
        } catch (e) {
            utils.log("主线程异常: " + e);
            running = false;
            ui.run(function () {
                window.status.setText("异常停止");
                window.status.setTextColor(colors.parseColor("#f44336"));
            });
        }
    });
}

function stopCollector() {
    running = false;
    if (mainThread) {
        mainThread.interrupt();
        mainThread = null;
    }
    ui.run(function () {
        window.status.setText("已停止");
        window.status.setTextColor(colors.parseColor("#aaaaaa"));
    });
    toast("已发送停止信号");
    appendLog("用户手动停止");
}

// ==================== 主逻辑 ====================

function runCollector() {
    utils.log("========================================");
    utils.log("企业微信消息采集转发 - 启动");
    utils.log("源群: " + config.sourceGroup);
    utils.log("目标群 (" + config.targetGroups.length + "个): " + config.targetGroups.join(", "));
    utils.log("回溯: " + config.lookbackMinutes + " 分钟");
    utils.log("轮询间隔: " + config.pollIntervalSeconds + " 秒");
    utils.log("========================================");

    // 初始化
    storage.init();

    // 初始化 OCR（可选）
    try {
        var ocr = require("./modules/ocr.js");
        ocr.init();
    } catch (e) {
        utils.log("OCR 模块加载失败（非必须）: " + e);
    }

    // 确保企业微信在前台
    if (!utils.ensureWeWorkForeground()) {
        utils.log("无法启动企业微信，退出");
        running = false;
        return;
    }

    // ===== Phase 1: 进入源群并执行首次转发 =====
    utils.log("===== Phase 1: 进入源群 =====");

    var enterOk = navigator.enterGroup(config.sourceGroup);
    if (!enterOk) {
        utils.log("无法进入源群: " + config.sourceGroup + "，退出");
        running = false;
        return;
    }

    utils.log("已进入源群，等待 " + config.enterGroupWaitSeconds + " 秒...");
    utils.waitSeconds(config.enterGroupWaitSeconds);

    // 检查是否有新消息需要转发
    if (collector.hasNewMessages()) {
        utils.log("发现新消息，执行首次转发...");
        var success = forwarder.forwardNewMessages();
        if (success) {
            utils.log("首次转发完成");
        } else {
            utils.log("首次转发失败，继续进入监控模式");
        }

        // 转发完后确保回到源群
        navigator.enterGroup(config.sourceGroup);
        utils.waitSeconds(config.enterGroupWaitSeconds);
    } else {
        utils.log("没有新消息，直接进入监控模式");
        // 记录当前最后一条消息作为书签起点
        var lastMsg = collector.getLastMessage();
        if (lastMsg && !storage.getBookmark()) {
            storage.saveBookmark(lastMsg.sender, lastMsg.content, lastMsg.time);
            utils.log("已记录初始书签");
        }
    }

    // ===== Phase 2: 持续监控新消息 =====
    utils.log("===== Phase 2: 持续监控新消息 =====");

    var consecutiveErrors = 0;
    var maxConsecutiveErrors = 10;

    while (running) {
        try {
            // 等待轮询间隔（可中断）
            utils.log("等待 " + config.pollIntervalSeconds + " 秒后检查...");
            for (var w = 0; w < config.pollIntervalSeconds && running; w++) {
                sleep(1000);
            }
            if (!running) break;

            // 确保企业微信在前台
            if (!utils.isInWeWork()) {
                utils.log("企业微信不在前台，尝试恢复...");
                utils.ensureWeWorkForeground();
                utils.waitExact(2000);
                navigator.enterGroup(config.sourceGroup);
                utils.waitSeconds(config.enterGroupWaitSeconds);
            }

            // 检查是否有新消息
            if (collector.hasNewMessages()) {
                utils.log("发现新消息，开始转发...");
                var success = forwarder.forwardNewMessages();
                if (success) {
                    utils.log("转发完成");
                } else {
                    utils.log("转发失败");
                }

                // 转发后回到源群
                navigator.enterGroup(config.sourceGroup);
                utils.waitSeconds(config.enterGroupWaitSeconds);
            } else {
                utils.debug("暂无新消息");
            }

            consecutiveErrors = 0;

            // 定期清理过期指纹
            if (Math.random() < 0.01) {
                storage.cleanOldFingerprints();
            }

        } catch (e) {
            consecutiveErrors++;
            utils.log("轮询异常 (" + consecutiveErrors + "/" + maxConsecutiveErrors + "): " + e);

            if (consecutiveErrors >= maxConsecutiveErrors) {
                utils.log("连续错误次数过多，停止运行");
                running = false;
                break;
            }

            // 异常恢复
            utils.waitSeconds(5);
            try {
                navigator.goToMessageList();
                utils.waitExact(1000);
                navigator.enterGroup(config.sourceGroup);
                utils.waitSeconds(config.enterGroupWaitSeconds);
            } catch (e2) {
                utils.log("恢复失败: " + e2);
            }
        }
    }

    utils.log("========================================");
    utils.log("采集任务已停止");
    utils.log("========================================");

    ui.run(function () {
        window.status.setText("已停止");
        window.status.setTextColor(colors.parseColor("#aaaaaa"));
    });
}

// ==================== 初始化 ====================

// 加载上次保存的配置
var hasConfig = loadUserConfig();

if (hasConfig) {
    appendLog("已加载配置: 源群=" + config.sourceGroup +
        " 目标群=" + config.targetGroups.join(","));
} else {
    appendLog("首次运行，请点击[启动]或[设置]配置群信息");
}

// 脚本退出时清理
events.on("exit", function () {
    running = false;
    utils.log("脚本退出");
});

// 保持脚本运行（悬浮窗模式需要主线程不退出）
setInterval(function () {}, 1000);
