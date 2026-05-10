# CONTEXT_HANDOFF

## 当前目标
- 修复分割线已命中但锚点选到后续消息的问题，保持当前可用的目标群选择逻辑不动。

## 已完成
- 在 `MessageCollector.kt` 的 `findFirstBubbleBelow()` 中增加疑似消息行判断。
- 分割线下方第一条疑似消息解析失败时，不再继续跳到后续消息，而是返回 `null` 触发现有 `swipeDown` 重试。

## 已修改文件
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageCollector.kt`
- `CONTEXT_HANDOFF.md`

## 关键决策
- 冻结 `selectTargetGroups()`，不修改选群流程。
- 不修改 `MessageForwarder.kt` 的批次、长按、多选、发送流程。
- 当前修复优先避免漏选第一条未读，遇到疑似消息但解析失败时宁可微调重试。

## 未完成事项
- 本地未完成 Gradle 编译验证，当前环境缺 Java Runtime。
- 需要真机用 7 条未读、第一条为卡片/复杂消息的场景验证。

## 下一步
- 如需发布，提交 `MessageCollector.kt` 和 `CONTEXT_HANDOFF.md`，排除未跟踪的 `1.jpg`。
- 真机重点观察日志：`第一条疑似消息解析失败，不跳到后续消息，swipeDown 重试`。

## 已知问题
- CI 只能验证编译，不能证明企微无障碍 UI 行为正确。
- `1.jpg` 仍为未跟踪文件，本次不应纳入提交。
