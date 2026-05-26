# CONTEXT_HANDOFF

## 当前目标
- 将悬浮日志窗改为肉眼不可见但仍保留 overlay 实例，任务控制入口改由 App 主界面承担，并触发 CI 构建。

## 已完成
- `FloatingLogView` 默认创建 1x1、全透明、不可触摸、不可聚焦的隐藏 overlay；日志仍可写入内存/文件。
- `CollectorService` 启动前台服务后自动开始采集，不再等待悬浮窗“开始”按钮。
- 主界面悬浮窗权限提示改为“创建隐藏状态窗口”。
- 保留悬浮窗权限与 overlay 创建逻辑，转发/选群/采集核心流程未改。

## 已修改文件
- `android/WeworkForwarder/app/src/main/AndroidManifest.xml`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/CollectorService.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/Config.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/FloatingLogView.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MainActivity.kt`
- `CONTEXT_HANDOFF.md`

## 关键决策
- 不新增 UI 开关：悬浮窗始终隐藏。
- 隐藏方式不是 `removeView`，而是保留 `WindowManager` overlay，设置 1x1 + alpha 0 + `FLAG_NOT_TOUCHABLE`。
- 因悬浮窗不可见，任务启动由主界面“开始”触发服务后自动运行。

## 验证情况
- 本地执行 `./gradlew assembleDebug` 通过（设置 `JAVA_HOME=/opt/homebrew/opt/openjdk@17`、`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`）。
- 构建仅有既有 Kotlin warning，未出现本次改动相关错误。

## 未完成事项
- 待提交并推送 tag 触发 GitHub CI。
- 待真机验证：企微前台肉眼不可见、任务自动开始、主界面停止生效。

## 已知问题
- 未跟踪文件 `1.jpg` 和 `企微群转发.apk.1` 不属于本次改动，不应提交。
