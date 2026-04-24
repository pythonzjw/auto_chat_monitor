/**
 * 消息转发模块
 * 
 * 负责将采集到的消息通过企业微信的转发功能转发到目标群
 * 核心流程：长按消息 → 多选 → 勾选(最多9条) → 转发 → 选目标群 → 确认
 */

var config = require("../config.js");
var utils = require("./utils.js");
var storage = require("./storage.js");

var forwarder = {

    /**
     * 转发消息到所有目标群
     * 
     * 流程：
     * 1. 在源群聊天页面中，长按第一条要转发的消息
     * 2. 点击"多选"
     * 3. 勾选需要转发的消息（每次最多9条）
     * 4. 点击底部"转发"按钮
     * 5. 搜索并选择目标群
     * 6. 确认发送
     * 7. 对每个目标群重复步骤4-6
     * 
     * @param {object[]} messages 要转发的消息数组（带 fingerprint 字段）
     * @returns {boolean} 是否全部转发成功
     */
    forwardMessages: function (messages) {
        if (!messages || messages.length === 0) {
            utils.debug("没有需要转发的消息");
            return true;
        }

        utils.log("开始转发 " + messages.length + " 条消息到 " + config.targetGroups.length + " 个目标群");

        // 按每批最多9条分批
        var batches = forwarder._splitBatches(messages, config.maxForwardCount);
        utils.log("共分 " + batches.length + " 批进行转发");

        var allSuccess = true;

        for (var b = 0; b < batches.length; b++) {
            var batch = batches[b];
            utils.log("=== 第 " + (b + 1) + "/" + batches.length + " 批，" + batch.length + " 条消息 ===");

            // 步骤1：进入多选模式并选中消息
            var selectOk = forwarder._enterMultiSelectAndPick(batch);
            if (!selectOk) {
                utils.log("进入多选模式失败，跳过这批");
                allSuccess = false;
                continue;
            }

            // 步骤2：对每个目标群执行转发
            for (var t = 0; t < config.targetGroups.length; t++) {
                var targetGroup = config.targetGroups[t];
                utils.log("转发到目标群: " + targetGroup);

                var fwdOk = forwarder._doForward(targetGroup);
                if (fwdOk) {
                    utils.log("转发成功: " + targetGroup);
                } else {
                    utils.log("转发失败: " + targetGroup);
                    allSuccess = false;
                }

                utils.waitSeconds(config.targetGroupIntervalSeconds);
            }

            // 标记这批消息为已转发
            var fps = batch.map(function (m) { return m.fingerprint; });
            storage.markForwardedBatch(fps);

            // 保存消息记录
            storage.saveMessages(batch);

            // 退出多选模式
            forwarder._exitMultiSelect();

            utils.waitSeconds(config.forwardIntervalSeconds);
        }

        return allSuccess;
    },

    /**
     * 进入多选模式并勾选消息
     * 
     * @param {object[]} batch 要选中的消息批次
     * @returns {boolean}
     */
    _enterMultiSelectAndPick: function (batch) {
        if (batch.length === 0) return false;

        // 找到第一条消息对应的控件并长按
        var firstMsg = forwarder._findMessageElement(batch[0]);
        if (!firstMsg) {
            utils.log("找不到第一条消息的控件，尝试通过内容文本定位");
            // 兜底：直接找内容文本
            firstMsg = text(batch[0].content).findOne(2000);
            if (!firstMsg) {
                utils.log("仍然找不到消息控件");
                return false;
            }
        }

        // 长按消息弹出菜单
        utils.debug("长按消息...");
        press(firstMsg.bounds().centerX(), firstMsg.bounds().centerY(), config.longPressDuration);
        utils.wait(config.clickDelay);

        // 点击"多选"按钮
        var multiSelect = text("多选").findOne(3000)
            || desc("多选").findOne(2000);
        if (!multiSelect) {
            utils.log("找不到'多选'按钮");
            // 尝试点击屏幕其他地方关闭菜单
            click(device.width / 2, device.height * 0.1);
            utils.wait(500);
            return false;
        }

        utils.safeClick(multiSelect);
        utils.wait(800);

        // 现在进入了多选模式，第一条消息已经被选中
        // 需要选中其他消息
        for (var i = 1; i < batch.length; i++) {
            var msgElem = forwarder._findMessageElement(batch[i]);
            if (msgElem) {
                // 在多选模式下，点击消息左侧的复选框来选中
                // 复选框通常在消息左侧
                var bounds = msgElem.bounds();
                // 点击消息左侧（复选框区域）
                click(bounds.left - 40, bounds.centerY());
                utils.wait(400);
            } else {
                // 找不到控件，尝试通过文本定位
                var textElem = text(batch[i].content).findOne(1500);
                if (textElem) {
                    var b = textElem.bounds();
                    click(b.left - 40, b.centerY());
                    utils.wait(400);
                }
            }
        }

        utils.debug("已选中 " + batch.length + " 条消息");
        return true;
    },

    /**
     * 执行转发操作到指定群
     * 前提：已经在多选模式中，消息已选中
     * 
     * @param {string} targetGroupName 目标群名称
     * @returns {boolean}
     */
    _doForward: function (targetGroupName) {
        // 步骤1：点击底部的"转发"按钮
        var forwardBtn = text("转发").findOne(3000)
            || desc("转发").findOne(2000)
            || id("bff").findOne(2000);  // 企业微信转发按钮的可能 id

        if (!forwardBtn) {
            utils.log("找不到'转发'按钮");
            return false;
        }

        utils.safeClick(forwardBtn);
        utils.wait(1000);

        // 步骤2：可能会弹出"逐条转发"/"合并转发"选项
        var mergeForward = text("逐条转发").findOne(2000);
        if (mergeForward) {
            // 选择逐条转发（保留原格式）
            utils.safeClick(mergeForward);
            utils.wait(800);
        }

        // 步骤3：进入选择转发目标页面，搜索目标群
        var searchInput = className("EditText").findOne(3000);
        if (!searchInput) {
            utils.log("转发页面找不到搜索框");
            utils.goBack();
            return false;
        }

        searchInput.setText(targetGroupName);
        utils.wait(1500); // 等待搜索结果

        // 步骤4：点击搜索结果中的目标群
        var targetResult = text(targetGroupName).findOne(3000);
        if (!targetResult) {
            utils.log("搜索结果中未找到目标群: " + targetGroupName);
            // 清空搜索框并返回
            searchInput.setText("");
            utils.wait(500);
            utils.goBack();
            return false;
        }

        utils.safeClick(targetResult);
        utils.wait(800);

        // 步骤5：确认发送（可能弹出确认对话框）
        var confirmBtn = text("发送").findOne(2000)
            || text("确定").findOne(2000)
            || text("确认").findOne(2000);

        if (confirmBtn) {
            utils.safeClick(confirmBtn);
            utils.wait(1000);
        }

        // 验证是否发送成功（检查是否出现"已转发"提示或回到了聊天页面）
        utils.debug("转发到 " + targetGroupName + " 操作完成");
        return true;
    },

    /**
     * 退出多选模式
     */
    _exitMultiSelect: function () {
        // 点击取消/关闭按钮退出多选模式
        var cancelBtn = text("取消").findOne(2000)
            || desc("取消").findOne(2000)
            || desc("关闭").findOne(2000);

        if (cancelBtn) {
            utils.safeClick(cancelBtn);
        } else {
            // 按返回键退出
            utils.goBack();
        }
        utils.wait(500);
    },

    /**
     * 查找消息对应的 UI 元素
     * @param {object} msg 消息对象
     * @returns {UiObject|null}
     */
    _findMessageElement: function (msg) {
        // 优先通过消息内容文本查找
        if (msg.content && msg.content !== "[图片]" && msg.content !== "[文件]") {
            var elem = text(msg.content).findOne(1500);
            if (elem) return elem;

            // 尝试部分匹配（消息过长可能被截断显示）
            if (msg.content.length > 20) {
                elem = textContains(msg.content.substring(0, 20)).findOne(1500);
                if (elem) return elem;
            }
        }

        // 通过发送人查找，然后定位到其消息内容
        if (msg.sender && msg.sender !== "未知" && msg.sender !== "系统") {
            var senderElem = text(msg.sender).findOne(1500);
            if (senderElem) {
                // 消息内容通常在发送人的下方或右侧
                var parent = senderElem.parent();
                if (parent) {
                    // 在同一容器中找文本内容节点
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

    /**
     * 将消息数组分批
     * @param {object[]} messages 消息数组
     * @param {number} batchSize 每批数量
     * @returns {object[][]} 分批后的二维数组
     */
    _splitBatches: function (messages, batchSize) {
        var batches = [];
        for (var i = 0; i < messages.length; i += batchSize) {
            batches.push(messages.slice(i, i + batchSize));
        }
        return batches;
    },

    /**
     * 单条消息直接转发（不用多选模式）
     * 适用于实时监控时只有一条新消息的场景
     * 
     * @param {object} msg 消息对象
     * @returns {boolean}
     */
    forwardSingleMessage: function (msg) {
        var elem = forwarder._findMessageElement(msg);
        if (!elem) {
            utils.log("找不到消息控件，无法转发");
            return false;
        }

        // 长按消息
        press(elem.bounds().centerX(), elem.bounds().centerY(), config.longPressDuration);
        utils.wait(config.clickDelay);

        // 点击"转发"
        var fwdBtn = text("转发").findOne(3000);
        if (!fwdBtn) {
            utils.log("长按菜单中找不到'转发'选项");
            click(device.width / 2, device.height * 0.1); // 关闭菜单
            return false;
        }

        utils.safeClick(fwdBtn);
        utils.wait(800);

        // 对每个目标群转发
        var success = true;
        for (var t = 0; t < config.targetGroups.length; t++) {
            var targetGroup = config.targetGroups[t];

            var searchInput = className("EditText").findOne(3000);
            if (!searchInput) {
                success = false;
                continue;
            }

            searchInput.setText(targetGroup);
            utils.wait(1500);

            var result = text(targetGroup).findOne(3000);
            if (result) {
                utils.safeClick(result);
                utils.wait(800);

                var confirmBtn = text("发送").findOne(2000)
                    || text("确定").findOne(2000);
                if (confirmBtn) {
                    utils.safeClick(confirmBtn);
                    utils.wait(1000);
                }
            } else {
                success = false;
            }

            utils.waitSeconds(config.targetGroupIntervalSeconds);
        }

        // 标记为已转发
        storage.markForwarded(msg.fingerprint);
        storage.saveMessage(msg);

        return success;
    }
};

module.exports = forwarder;
