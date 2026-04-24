/**
 * 企业微信群消息采集转发 - 配置文件
 * 
 * 使用前请根据实际情况修改以下配置
 */

var config = {
    // ==================== 群配置 ====================

    // 源群名称（要监控采集消息的群）
    sourceGroup: "测试源群",

    // 目标群名称列表（转发时在选群页面勾选这些群，最多9个）
    targetGroups: [
        "目标群1",
        "目标群2",
        "目标群3"
    ],

    // ==================== 时间配置 ====================

    // 启动时回溯历史消息的分钟数（启动后先往上翻，采集这么多分钟前的消息）
    lookbackMinutes: 10,

    // 进入群聊后等待多少秒再开始操作（等待消息加载完成）
    enterGroupWaitSeconds: 3,

    // 每轮轮询间隔秒数（采集完一轮后等待多久再检查新消息）
    pollIntervalSeconds: 30,

    // ==================== 操作延时配置（毫秒） ====================

    // 点击操作后的等待时间（基准值，实际会加随机偏移）
    clickDelay: 800,

    // 滑动操作后的等待时间（基准值）
    swipeDelay: 1000,

    // 长按操作的持续时间
    longPressDuration: 600,

    // 等待页面加载的超时时间
    pageLoadTimeout: 5000,

    // 搜索群名后等待搜索结果的时间
    searchWaitDelay: 1500,

    // 随机延时范围（在基准延时上额外加 0~此值 的随机毫秒数，模拟真人）
    randomDelayMax: 500,

    // 点击坐标随机偏移像素范围（点击位置会在目标点上下左右偏移 0~此值 像素）
    clickOffsetMax: 8,

    // ==================== 存储配置 ====================

    // 数据保存目录（手机上的路径）
    dataDir: "/sdcard/wework-collector/",

    // 消息记录文件名
    messageFile: "messages.json",

    // 已转发消息指纹文件名（防重复）
    fingerprintFile: "fingerprints.json",

    // 书签文件名（记录上次转发到哪条消息）
    bookmarkFile: "bookmark.json",

    // 日志文件名
    logFile: "collector.log",

    // ==================== 企业微信包名 ====================

    // 企业微信的包名
    packageName: "com.tencent.wework",

    // ==================== 调试配置 ====================

    // 是否开启调试模式（打印更多日志）
    debug: true,

    // 是否在采集时截图保存（用于调试）
    saveScreenshot: false
};

module.exports = config;
