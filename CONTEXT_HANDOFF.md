# CONTEXT_HANDOFF

## 当前目标
- Android 客户端从旧 `5002 /verify?code=` 白名单授权切到统一授权中心 `5003 /api/verify`，并支持静默失败、60 秒心跳、请求签名和 release 混淆。

## 已完成
- `LicenseManager` 改为 POST 统一授权中心，携带 `project_key/machine_id/session_id/lease_token/timestamp/nonce/signature`。
- 本地保存并复用 `session_id/lease_token/lease_seconds`。
- 保活间隔改为 60 秒。
- 授权失败不弹窗、不 toast；静默停止采集并关闭界面。
- release 开启 R8/ProGuard 混淆和资源压缩，并保留 Gson JSON 字段与 Android 组件入口。

## 已修改文件
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/Config.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/LicenseManager.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MainActivity.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/Storage.kt`
- `android/WeworkForwarder/app/build.gradle.kts`
- `android/WeworkForwarder/app/proguard-rules.pro`
- `CONTEXT_HANDOFF.md`

## 关键决策
- 客户端签名 payload 与授权中心一致：`project_key\nmachine_id\nsession_id\nlease_token\ntimestamp\nnonce`。
- 客户端失败关闭，不做离线宽限。
- 授权中心地址：`http://47.116.98.81:5003/api/verify`。

## 验证情况
- `git diff --check` 通过。
- 本机无法执行 Gradle 编译：无 Java Runtime，`./gradlew :app:assembleRelease` 报 `Unable to locate a Java Runtime`。
- 线上授权中心接口已用 smoke 数据验证 10 台限制、续租、错误签名；smoke 数据已清理。

## 下一步
- 在 CI 或安装 JDK 17 的机器上构建 release APK。
- 真机验证：首次启动自动授权、60 秒心跳、授权失败无提示且不启动采集、采集转发原流程正常。

## 已知问题
- `1.jpg` 是未跟踪文件，不属于本次授权改造。
