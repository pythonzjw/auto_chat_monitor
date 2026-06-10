# CONTEXT_HANDOFF

## 当前目标
- 修复选群时计数暂时不可读导致重复点击同一群、把已选群取消的问题。

## 已完成
- `MessageForwarder.selectTargetGroups` 增加“已点击待最终确认”状态。
- 目标群点击后若 `确定(N)` 计数不可读：从待选移除，加入待确认集合，后续不再重复点击该群。
- 目标群点击后若计数明确 `+1`：按已确认选中处理。
- 若计数下降：判定疑似误取消，立即停止本批，避免继续误操作。
- 发送前强制读取最终 `确定(N)`，必须等于本批去重目标数才点击确定；否则拒绝部分发送。

## 已修改文件
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageForwarder.kt`
- `CONTEXT_HANDOFF.md`

## 关键决策
- 不加搜索、不加蓝勾识别、不允许部分发送。
- “计数不可读”不代表失败，也不代表成功；只表示已点过待最终总数确认。
- 通过最终 `确定(N)` 满额来保证 9 个目标全部选中。

## 验证情况
- `git diff --check` 通过。
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew assembleDebug` 通过。

## 未完成事项
- 待真机验证：83 首次计数不可读后不再二次点击取消；最终 `确定(9)` 才发送。
- 待按需提交、打 tag、触发 CI。

## 已知问题
- 未跟踪文件 `1.jpg` 不属于本次改动，不应提交。
