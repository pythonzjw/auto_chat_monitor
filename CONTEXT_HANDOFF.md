# CONTEXT_HANDOFF

## 当前目标
- 提升企微转发稳定性：小程序卡片可长按转发、避免误选旧消息、修复选群页左下角误点，并恢复小面积侧边小把手控制。

## 已完成
- `MessageForwarder` 增加卡片消息专用长按候选：优先卡片节点 `ACTION_LONG_CLICK`，再用 900ms 坐标长按，失败则本轮失败不误选旧消息。
- `scrollAndSelectToHere` 移除上半屏/最后可信坐标兜底，只在确认列表向底部移动并稳定后点击下半屏“选择到这里”。
- `selectTargetGroups` 删除左侧坐标兜底，只点击安全区内真实复选框/可点击节点；确定计数必须 +1 才算成功，计数下降判定误取消。
- `FloatingLogView` 改为右侧边缘小把手，点击展开开始/暂停/收回，5 秒自动收回；`CollectorService` 运行中保持小把手可触摸。

## 已修改文件
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageForwarder.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/FloatingLogView.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/CollectorService.kt`
- `CONTEXT_HANDOFF.md`

## 关键决策
- 小程序必须转发；长按失败时本轮失败并 dump，不跳到下一条、不选时间线上方旧消息。
- 选群页不再允许坐标兜底点左下角，宁可失败也不取消已选群。
- 主要按 720/1080 竖屏，通过比例 + dp + 安全区限制点击。
- 侧边控制使用小把手常驻，大面板仅短暂展开。

## 验证情况
- `git diff --check` 通过。
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew assembleDebug` 通过。

## 未完成事项
- 待真机验证：小程序作为第一条新消息、选群底部边缘、多目标群分批、侧边小把手展开/自动收回。
- 待按需提交、打 tag、触发 CI。

## 已知问题
- 未跟踪文件 `1.jpg` 不属于本次改动，不应提交。
