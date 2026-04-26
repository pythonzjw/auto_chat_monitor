# AGENTS.md

## 项目概述

企业微信群消息采集转发工具。**原生 Android Kotlin 应用**，使用 AccessibilityService 读取企微控件树并执行转发操作。标准 Gradle 构建，CI 自动打包 APK。

## 代码风格

- 注释用中文
- Kotlin，`official` 代码风格

## 项目结构

```
android/WeworkForwarder/          # Android 原生应用
  app/src/main/java/com/wework/forwarder/
    MainActivity.kt               # 主界面：配置群信息、启动/停止、日志显示
    WeWorkAccessibilityService.kt  # 无障碍服务：控件树读取、手势操作
    CollectorService.kt            # 前台服务：主循环（轮询→采集→转发）
    NodeFinder.kt                  # 控件查找工具（text/id/className/desc）
    GestureHelper.kt               # 手势工具（随机偏移、延时、滑动）
    MessageCollector.kt            # 消息采集（控件树结构→文本节点 两级降级）
    MessageForwarder.kt            # 转发流程（长按→多选→全选→转发→选群→发送）
    Navigator.kt                   # 页面导航（进群、返回列表、搜索）
    Storage.kt                     # 本地持久化（书签、消息、指纹、用户配置）
    TimeParser.kt                  # 企微时间文本解析
    Config.kt                      # 全局配置常量

# 以下为旧的 AutoJs6 脚本（已弃用，保留参考）
main.js / config.js / inspect.js / project.json / modules/*.js
```

## 关键设计决策

- **防重复转发**：书签在转发**前**保存（MessageForwarder 步骤3），不是转发后。崩溃重启不会重复，最坏情况是漏一批。
- **反检测**：所有点击/滑动加随机延时和坐标偏移（GestureHelper）。
- **转发流程**：一次全选所有新消息，选群页面逐个搜索勾选目标群（最多9个），一次性发送。
- **消息采集**：直接读取 AccessibilityService 控件树，不需要 OCR 或截图。
- **UI 选择器**：依赖企业微信具体版本的 UI 结构，不同版本可能需要调整。用"分析控件"按钮 dump 实际控件树。

## 构建与发布

- **技术栈**：Kotlin + Gradle 8.5 + AGP 8.2.0，JDK 17
- **触发方式**：推送 `v*` 格式的 git tag 触发 CI（`.github/workflows/build.yml`）
- **产出**：`wework-forwarder.apk` 上传到 GitHub Release
- **APK 大小**：约 5MB（无重型依赖）

```bash
# 发布流程
git add -A && git commit -m "描述"
git push origin main
git tag v2.0.0 && git push origin v2.0.0   # 触发 CI 打包
```

## CI 所需 Secrets

- `KEYSTORE_BASE64` — keystore 文件的 base64 编码
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## 注意事项

- `data/` 和 `*.apk` 已在 `.gitignore` 中，不要提交
- 运行时数据保存在手机 `/sdcard/wework-collector/`
- 企业微信包名 `com.tencent.wework` 硬编码在 `Config.kt`
- 无障碍服务配置 `accessibility_service_config.xml` 中 `packageNames` 限定只监听企微
