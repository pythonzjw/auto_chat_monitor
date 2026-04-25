# AGENTS.md

## 项目概述

企业微信群消息采集转发工具。AutoJs6 JavaScript 脚本项目，通过 CI 打包成独立 Android APK。**不是 Node.js 项目，不能用 `npm`/`node` 运行或测试。**

## 代码风格

- 注释用中文
- 只用 `var`，不用 `let`/`const`（Rhino 引擎兼容性）
- 用 `require()` / `module.exports`，不用 ES6 `import`/`export`
- UI 布局用 JSX 语法（AutoJs6 特有，文件开头声明 `"ui";`）

## 项目结构

```
main.js          # 入口（UI 界面 + 主逻辑）
config.js        # 所有可配置参数
project.json     # AutoJs6 打包清单（包名、权限、入口文件）
inspect.js       # 辅助工具：dump 企业微信 UI 控件树
modules/
  collector.js   # 消息采集（控件树 → 文本节点 → OCR 三级降级）
  forwarder.js   # 转发操作（长按→多选→选择到这里→转发→搜索群勾选→发送）
  navigator.js   # 页面导航（进群、返回列表、搜索群）
  storage.js     # 本地持久化（消息 JSON、指纹去重、书签）
  ocr.js         # OCR 兜底（依赖 AutoJs6 内置引擎，非自实现）
  utils.js       # 工具函数（随机延时、坐标偏移、日志、控件操作）
```

## 关键设计决策

- **防重复转发**：书签在转发**前**保存（`forwarder.js` 步骤3），不是转发后。崩溃重启不会重复，最坏情况是漏一批。
- **反检测**：所有点击/滑动加随机延时（`config.randomDelayMax`）和坐标偏移（`config.clickOffsetMax`）。
- **转发流程**：一次全选所有新消息，选群页面逐个搜索勾选目标群（最多9个），一次性发送。不是分批发消息。
- **UI 选择器**：依赖企业微信具体版本的 UI 结构，不同版本可能需要调整。先运行 `inspect.js` 抓取实际控件树再适配。

## 构建与发布

- **触发方式**：推送 `v*` 格式的 git tag 触发 CI（`.github/workflows/build.yml`）
- **打包工具**：`apkbuilder-cli.jar`（`Steven-Qiang/AutoJs6-ApkBuilder`），只需 Java 17，不需要 Android SDK
- **不要用** `AutoJs6-ApkBuilder` 的 GitHub Action（有 `context.repo` bug），直接用 CLI jar
- **产出**：`wework-collector.apk` 上传到 GitHub Release

```bash
# 发布流程
git add -A && git commit -m "描述"
git push origin main
git tag v1.2.3 && git push origin v1.2.3   # 触发 CI 打包
```

## 注意事项

- `project.json` 中 `mainScriptFileName` 必须和实际入口文件名一致（当前是 `main.js`）
- `data/` 和 `*.apk` 已在 `.gitignore` 中，不要提交
- 运行时数据保存在手机 `/sdcard/wework-collector/`
- 企业微信包名 `com.tencent.wework` 硬编码在 `config.js`
