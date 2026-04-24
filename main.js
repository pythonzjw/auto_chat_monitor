/**
 * 企业微信群消息采集转发脚本 - 主入口
 * 
 * 功能：
 *   1. 持续监控指定源群的消息
 *   2. 启动时先回溯 N 分钟的历史消息并转发
 *   3. 之后持续轮询，有新消息就转发到所有目标群
 *   4. 消息本地备份到 JSON 文件
 * 
 * 运行环境：Auto.js / Hamibot
 * 前提条件：
 *   - 已授予无障碍服务权限
 *   - 企业微信已登录
 *   - 手机屏幕保持常亮
 * 
 * 使用方法：
 *   1. 修改 config.js 中的群名称和参数
 *   2. 在 Auto.js 中运行本脚本
 */

"ui";

// ==================== 加载模块 ====================
var config = require("./config.js");
var utils = require("./modules/utils.js");
var storage = require("./modules/storage.js");
var navigator = require("./modules/navigator.js");
var collector = require("./modules/collector.js");
var forwarder = require("./modules/forwarder.js");

// ==================== UI 界面 ====================
// 提供一个简单的配置和启动界面

ui.layout(
    <vertical padding="16">
        <text text="企业微信消息采集转发" textSize="20sp" textStyle="bold" gravity="center" marginBottom="16" />

        <horizontal>
            <text text="源群名称:" textSize="14sp" w="80" />
            <input id="sourceGroup" text="" textSize="14sp" w="*" />
        </horizontal>

        <horizontal marginTop="8">
            <text text="目标群:" textSize="14sp" w="80" />
            <input id="targetGroups" text="" textSize="14sp" w="*" hint="多个群用逗号分隔" />
        </horizontal>

        <horizontal marginTop="8">
            <text text="回溯分钟:" textSize="14sp" w="80" />
            <input id="lookback" text="10" textSize="14sp" inputType="number" w="100" />
        </horizontal>

        <horizontal marginTop="8">
            <text text="进群等待(秒):" textSize="14sp" w="100" />
            <input id="enterWait" text="3" textSize="14sp" inputType="number" w="100" />
        </horizontal>

        <horizontal marginTop="8">
            <text text="轮询间隔(秒):" textSize="14sp" w="100" />
            <input id="pollInterval" text="15" textSize="14sp" inputType="number" w="100" />
        </horizontal>

        <horizontal marginTop="16" gravity="center">
            <button id="btnStart" text="启动采集" w="120" style="Widget.AppCompat.Button.Colored" />
            <button id="btnStop" text="停止" w="100" marginLeft="16" />
        </horizontal>

        <text text="运行日志:" textSize="14sp" marginTop="16" />
        <scroll w="*" h="*">
            <text id="logArea" text="" textSize="12sp" textColor="#333333" />
        </scroll>
    </vertical>
);

// 填充默认值
ui.sourceGroup.setText(config.sourceGroup);
ui.targetGroups.setText(config.targetGroups.join(","));
ui.lookback.setText(String(config.lookbackMinutes));
ui.enterWait.setText(String(config.enterGroupWaitSeconds));
ui.pollInterval.setText(String(config.pollIntervalSeconds));

// 控制标志
var running = false;
var mainThread = null;

/**
 * 追加日志到 UI
 */
function appendLog(msg) {
    var line = "[" + utils.now() + "] " + msg;
    ui.run(function () {
        var current = ui.logArea.getText() || "";
        // 限制日志行数，防止内存溢出
        var lines = current.split("\n");
        if (lines.length > 200) {
            lines = lines.slice(lines.length - 150);
        }
        lines.push(line);
        ui.logArea.setText(lines.join("\n"));
    });
}

// 拦截日志输出到 UI
var originalLog = utils.log;
utils.log = function (msg) {
    originalLog.call(utils, msg);
    appendLog(msg);
};

// ==================== 启动按钮 ====================
ui.btnStart.on("click", function () {
    if (running) {
        toast("已经在运行中");
        return;
    }

    // 读取 UI 上的配置
    config.sourceGroup = ui.sourceGroup.getText().toString().trim();
    var targetStr = ui.targetGroups.getText().toString().trim();
    config.targetGroups = targetStr.split(/[,，]/).map(function (s) { return s.trim(); }).filter(function (s) { return s !== ""; });
    config.lookbackMinutes = parseInt(ui.lookback.getText().toString()) || 10;
    config.enterGroupWaitSeconds = parseInt(ui.enterWait.getText().toString()) || 3;
    config.pollIntervalSeconds = parseInt(ui.pollInterval.getText().toString()) || 15;

    // 校验
    if (!config.sourceGroup) {
        toast("请填写源群名称");
        return;
    }
    if (config.targetGroups.length === 0) {
        toast("请填写至少一个目标群");
        return;
    }

    running = true;
    toast("采集任务已启动");

    // 在新线程中运行主逻辑
    mainThread = threads.start(function () {
        try {
            runCollector();
        } catch (e) {
            utils.log("主线程异常: " + e);
            running = false;
        }
    });
});

// ==================== 停止按钮 ====================
ui.btnStop.on("click", function () {
    if (!running) {
        toast("当前没有运行中的任务");
        return;
    }

    running = false;
    if (mainThread) {
        mainThread.interrupt();
        mainThread = null;
    }
    toast("已发送停止信号");
    appendLog("用户手动停止");
});

// ==================== 主逻辑 ====================

function runCollector() {
    utils.log("========================================");
    utils.log("企业微信消息采集转发 - 启动");
    utils.log("源群: " + config.sourceGroup);
    utils.log("目标群: " + config.targetGroups.join(", "));
    utils.log("回溯: " + config.lookbackMinutes + " 分钟");
    utils.log("进群等待: " + config.enterGroupWaitSeconds + " 秒");
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

    // ===== Phase 1: 采集历史消息 =====
    utils.log("===== Phase 1: 采集历史消息 =====");

    var enterOk = navigator.enterGroup(config.sourceGroup);
    if (!enterOk) {
        utils.log("无法进入源群: " + config.sourceGroup + "，退出");
        running = false;
        return;
    }

    utils.log("已进入源群，等待 " + config.enterGroupWaitSeconds + " 秒...");
    utils.waitSeconds(config.enterGroupWaitSeconds);

    // 采集历史消息
    var historyMessages = collector.collectHistoryMessages(config.lookbackMinutes);
    utils.log("历史消息采集完成: " + historyMessages.length + " 条");

    // 转发历史消息
    if (historyMessages.length > 0) {
        utils.log("开始转发历史消息...");
        // 先滚动到最底部，确保最新的消息可见
        for (var i = 0; i < 3; i++) {
            utils.swipeDown();
        }
        utils.wait(1000);

        forwarder.forwardMessages(historyMessages);
        utils.log("历史消息转发完成");
    }

    // ===== Phase 2: 持续监控新消息 =====
    utils.log("===== Phase 2: 持续监控新消息 =====");

    var consecutiveErrors = 0;
    var maxConsecutiveErrors = 10;

    while (running) {
        try {
            // 确保企业微信在前台
            if (!utils.isInWeWork()) {
                utils.log("企业微信不在前台，尝试恢复...");
                utils.ensureWeWorkForeground();
                utils.wait(2000);
                navigator.enterGroup(config.sourceGroup);
                utils.waitSeconds(config.enterGroupWaitSeconds);
            }

            // 方案A：直接在源群页面检查新消息
            // 如果当前还在源群聊天页面，直接采集
            var newMessages = collector.collectNewMessages();

            if (newMessages.length > 0) {
                utils.log("发现 " + newMessages.length + " 条新消息，开始转发...");
                forwarder.forwardMessages(newMessages);
                utils.log("新消息转发完成");

                // 转发完后回到源群继续监控
                navigator.enterGroup(config.sourceGroup);
                utils.waitSeconds(config.enterGroupWaitSeconds);
            } else {
                utils.debug("暂无新消息");
            }

            consecutiveErrors = 0;

            // 等待下一轮轮询
            utils.log("等待 " + config.pollIntervalSeconds + " 秒后再次检查...");
            for (var w = 0; w < config.pollIntervalSeconds && running; w++) {
                sleep(1000);
            }

            // 定期清理过期指纹（每100轮清理一次）
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

            // 异常后等待一段时间再重试
            utils.waitSeconds(5);

            // 尝试恢复：回到消息列表再进入源群
            try {
                navigator.goToMessageList();
                utils.wait(1000);
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
}

// 脚本退出时清理
events.on("exit", function () {
    running = false;
    utils.log("脚本退出");
});
