# CONTEXT_HANDOFF

## 当前目标
- 授权失败时不退出 App、不提示用户，界面保留但功能按钮静默无反应，并触发 CI 构建。

## 已完成
- `MainActivity` 增加本地授权状态标记。
- 首次授权成功后才初始化配置、按钮、日志和保活。
- 首次授权失败或保活失败时仅静默停止采集服务，不再 `finish()` 关闭界面。
- 授权失效后开始、停止、分析控件、导出和无障碍入口均直接返回，不弹 Toast。

## 已修改文件
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MainActivity.kt`
- `CONTEXT_HANDOFF.md`

## 关键决策
- 不改授权中心、机器码、签名或自动授权协议。
- 授权失败的用户体验定义为“无提示、无动作、界面不退出”。
- 已授权运行中保活失败时，立即请求停止采集并停止前台服务。

## 验证情况
- `git diff --check` 通过。
- 本地执行 `./gradlew assembleDebug` 通过（设置 `JAVA_HOME=/opt/homebrew/opt/openjdk@17`、`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`）。

## 未完成事项
- 待提交并推送 tag 触发 GitHub CI。
- 待真机验证：未授权/网络失败时 App 不闪退且按钮无反应；已授权流程正常。

## 已知问题
- 未跟踪文件 `1.jpg` 和 `企微群转发.apk.1` 不属于本次改动，不应提交。
