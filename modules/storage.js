/**
 * 本地存储模块
 * 
 * 负责消息持久化、指纹去重、书签管理
 */

var config = require("../config.js");
var utils = require("./utils.js");

var storage = {
    // 已转发消息指纹集合（内存缓存）
    _fingerprints: {},

    // 消息列表缓存
    _messages: [],

    // 书签（上次转发的最后一条消息标识）
    _bookmark: null,

    /**
     * 初始化存储目录和文件
     */
    init: function () {
        files.ensureDir(config.dataDir);
        files.ensureDir(config.dataDir + "screenshots/");

        // 加载已有指纹
        var fpPath = config.dataDir + config.fingerprintFile;
        if (files.exists(fpPath)) {
            try {
                var data = files.read(fpPath);
                storage._fingerprints = JSON.parse(data);
                utils.log("已加载 " + Object.keys(storage._fingerprints).length + " 条指纹记录");
            } catch (e) {
                utils.log("指纹文件解析失败，将重新创建: " + e);
                storage._fingerprints = {};
            }
        }

        // 加载已有消息
        var msgPath = config.dataDir + config.messageFile;
        if (files.exists(msgPath)) {
            try {
                var data = files.read(msgPath);
                storage._messages = JSON.parse(data);
                utils.log("已加载 " + storage._messages.length + " 条消息记录");
            } catch (e) {
                utils.log("消息文件解析失败，将重新创建: " + e);
                storage._messages = [];
            }
        }

        // 加载书签
        var bmPath = config.dataDir + config.bookmarkFile;
        if (files.exists(bmPath)) {
            try {
                var data = files.read(bmPath);
                storage._bookmark = JSON.parse(data);
                utils.log("已加载书签: " + JSON.stringify(storage._bookmark));
            } catch (e) {
                utils.log("书签文件解析失败: " + e);
                storage._bookmark = null;
            }
        }

        utils.log("存储模块初始化完成，数据目录: " + config.dataDir);
    },

    // ==================== 书签管理 ====================

    /**
     * 保存书签（记录上次转发到的最后一条消息）
     * @param {string} sender 发送人
     * @param {string} content 消息内容（前30字）
     * @param {string} time 消息时间标签
     */
    saveBookmark: function (sender, content, time) {
        storage._bookmark = {
            sender: sender || "",
            content: (content || "").substring(0, 30),
            time: time || "",
            savedAt: utils.now()
        };
        try {
            var path = config.dataDir + config.bookmarkFile;
            files.write(path, JSON.stringify(storage._bookmark, null, 2));
            utils.debug("书签已保存: " + JSON.stringify(storage._bookmark));
        } catch (e) {
            utils.log("保存书签失败: " + e);
        }
    },

    /**
     * 获取当前书签
     * @returns {object|null} {sender, content, time} 或 null
     */
    getBookmark: function () {
        return storage._bookmark;
    },

    /**
     * 检查一条消息是否匹配书签
     * @param {string} sender 发送人
     * @param {string} content 消息内容
     * @returns {boolean}
     */
    matchesBookmark: function (sender, content) {
        if (!storage._bookmark) return false;
        var bmContent = storage._bookmark.content;
        var bmSender = storage._bookmark.sender;
        // 内容匹配（前30字）
        var contentMatch = (content || "").substring(0, 30) === bmContent;
        // 发送人匹配
        var senderMatch = (sender || "") === bmSender;
        return contentMatch && senderMatch;
    },

    // ==================== 指纹管理 ====================

    /**
     * 检查消息是否已经转发过
     */
    isForwarded: function (fp) {
        return !!storage._fingerprints[fp];
    },

    /**
     * 标记消息为已转发
     */
    markForwarded: function (fp) {
        storage._fingerprints[fp] = utils.now();
        storage._saveFingerprints();
    },

    /**
     * 批量标记为已转发
     */
    markForwardedBatch: function (fps) {
        var time = utils.now();
        for (var i = 0; i < fps.length; i++) {
            storage._fingerprints[fps[i]] = time;
        }
        storage._saveFingerprints();
    },

    // ==================== 消息记录 ====================

    /**
     * 保存消息记录
     */
    saveMessage: function (msg) {
        msg.collectTime = utils.now();
        storage._messages.push(msg);
        storage._saveMessages();
    },

    /**
     * 批量保存消息
     */
    saveMessages: function (msgs) {
        var time = utils.now();
        for (var i = 0; i < msgs.length; i++) {
            msgs[i].collectTime = time;
            storage._messages.push(msgs[i]);
        }
        storage._saveMessages();
    },

    /**
     * 清理过期指纹（超过7天的）
     */
    cleanOldFingerprints: function () {
        var cutoff = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000);
        var cutoffStr = cutoff.toISOString();
        var count = 0;
        var keys = Object.keys(storage._fingerprints);
        for (var i = 0; i < keys.length; i++) {
            if (storage._fingerprints[keys[i]] < cutoffStr) {
                delete storage._fingerprints[keys[i]];
                count++;
            }
        }
        if (count > 0) {
            utils.log("清理了 " + count + " 条过期指纹");
            storage._saveFingerprints();
        }
    },

    _saveFingerprints: function () {
        try {
            var path = config.dataDir + config.fingerprintFile;
            files.write(path, JSON.stringify(storage._fingerprints, null, 2));
        } catch (e) {
            utils.log("保存指纹文件失败: " + e);
        }
    },

    _saveMessages: function () {
        try {
            var path = config.dataDir + config.messageFile;
            files.write(path, JSON.stringify(storage._messages, null, 2));
        } catch (e) {
            utils.log("保存消息文件失败: " + e);
        }
    }
};

module.exports = storage;
