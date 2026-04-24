/**
 * 企业微信 UI 布局分析辅助脚本
 * 
 * 使用方法：
 *   1. 先打开企业微信，进入你要采集的群聊页面
 *   2. 在 Auto.js 中运行此脚本
 *   3. 脚本会 dump 当前页面的完整控件树到文件
 *   4. 根据输出结果调整 collector.js 和 navigator.js 中的选择器
 * 
 * 这个脚本非常重要！不同版本的企业微信控件结构不同，
 * 你需要先运行这个脚本看看你手机上的实际控件 ID 和类名。
 */

auto.waitFor();

var OUTPUT_DIR = "/sdcard/wework-collector/";
files.ensureDir(OUTPUT_DIR);

toast("3秒后开始分析当前页面，请保持企业微信在前台...");
sleep(3000);

/**
 * 递归遍历控件树
 */
function dumpNode(node, depth, result) {
    if (!node) return;

    var indent = "";
    for (var i = 0; i < depth; i++) indent += "  ";

    var info = indent + node.className();

    // ID
    var id = node.id();
    if (id) info += " | id=" + id;

    // 文本
    var text = node.text();
    if (text) info += " | text=\"" + text.substring(0, 50) + "\"";

    // 描述
    var desc = node.desc();
    if (desc) info += " | desc=\"" + desc.substring(0, 50) + "\"";

    // 边界
    var bounds = node.bounds();
    info += " | bounds=[" + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom + "]";

    // 可点击/可滚动
    if (node.clickable()) info += " | clickable";
    if (node.scrollable()) info += " | scrollable";
    if (node.checkable()) info += " | checkable";

    result.push(info);

    // 递归子节点
    for (var i = 0; i < node.childCount(); i++) {
        dumpNode(node.child(i), depth + 1, result);
    }
}

// 获取页面所有根节点
var roots = [];
var allNodes = [];

console.log("========== 开始分析 ==========");
console.log("包名: " + currentPackage());
console.log("活动: " + currentActivity());
console.log("==============================");

allNodes.push("包名: " + currentPackage());
allNodes.push("活动: " + currentActivity());
allNodes.push("时间: " + new Date().toLocaleString());
allNodes.push("设备: " + device.brand + " " + device.model);
allNodes.push("屏幕: " + device.width + " x " + device.height);
allNodes.push("==============================");
allNodes.push("");

// dump 整个控件树
var root = auto.rootInActiveWindow;
if (root) {
    dumpNode(root, 0, allNodes);
} else {
    allNodes.push("无法获取根节点！请确认已开启无障碍服务。");
}

// 保存到文件
var outputPath = OUTPUT_DIR + "ui_dump_" + Date.now() + ".txt";
files.write(outputPath, allNodes.join("\n"));
console.log("控件树已保存到: " + outputPath);

// 额外分析：找出聊天页面的关键控件
console.log("");
console.log("========== 关键控件分析 ==========");

// 查找所有 ListView / RecyclerView（消息列表容器）
var listViews = className("android.widget.ListView").find();
var recyclerViews = className("androidx.recyclerview.widget.RecyclerView").find();
console.log("ListView 数量: " + listViews.length);
console.log("RecyclerView 数量: " + recyclerViews.length);

// 查找所有 EditText（输入框）
var editTexts = className("EditText").find();
console.log("EditText 数量: " + editTexts.length);
for (var i = 0; i < editTexts.length; i++) {
    var et = editTexts[i];
    console.log("  EditText[" + i + "] id=" + et.id() + " hint=" + et.text() + " bounds=" + et.bounds());
}

// 查找所有带文本的 TextView
var textViews = className("android.widget.TextView").find();
console.log("TextView 数量: " + textViews.length);

// 列出前30个 TextView 的内容
var tvSummary = [];
for (var i = 0; i < Math.min(textViews.length, 30); i++) {
    var tv = textViews[i];
    var t = tv.text();
    if (t && t.trim() !== "") {
        tvSummary.push("  [" + i + "] \"" + t.substring(0, 40) + "\" id=" + tv.id() + " bounds=" + tv.bounds());
    }
}
console.log("前30个有文本的 TextView:");
for (var i = 0; i < tvSummary.length; i++) {
    console.log(tvSummary[i]);
}

toast("分析完成！结果保存在: " + outputPath);
console.log("=================================");
console.log("分析完成！请查看文件: " + outputPath);
console.log("根据分析结果调整 config.js 和各模块中的控件选择器。");
