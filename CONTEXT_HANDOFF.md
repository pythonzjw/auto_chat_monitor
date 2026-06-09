# CONTEXT_HANDOFF

## 当前目标
- 最小修复：采集运行时常驻底部黑色状态条；转发成功/失败后回企微消息首页继续监听，不误退手机桌面。

## 已完成
- `FloatingLogView` 保留右侧小把手，并新增不可触摸的底部黑色状态条。
- 运行/等待时底部显示“监控采集群消息中...”，异常时显示“转发异常，正在恢复...”，停止后隐藏。
- `CollectorService` 转发结束后不再直接 `exitGroup()`，改用 `Navigator.goToMessageList()` 收敛回企微消息页。
- 本次未改选群逻辑、蓝勾识别、搜索流程、缺失群整批拒绝策略。

## 已修改文件
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/FloatingLogView.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/CollectorService.kt`
- `CONTEXT_HANDOFF.md`

## 关键决策
- “返回桌面”确认指企微消息首页，不是系统桌面；不新增 HOME 行为。
- 状态黑条采集期间常驻，但设置为不可触摸，避免影响企微手势。
- 74 群不存在属于配置/账号权限问题，本次不通过搜索绕过。

## 验证情况
- `git diff --check` 通过。
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew assembleDebug` 通过。

## 未完成事项
- 待真机验证：底部黑条位置是否合适；成功/失败后是否稳定回企微消息页；右侧小把手是否仍可暂停。
- 待按需提交、打 tag、触发 CI。

## 已知问题
- 未跟踪文件 `1.jpg` 不属于本次改动，不应提交。
