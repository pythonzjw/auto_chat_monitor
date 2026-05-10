# CONTEXT_HANDOFF

## 当前目标
- 修复消息选择阶段误长按、长按菜单弹出但未进入多选后继续滑动扩选的问题，保持当前可用的目标群选择逻辑不动。

## 已完成
- 在 `MessageCollector.kt` 的 `findFirstBubbleBelow()` 中增加疑似消息行判断。
- 分割线下方第一条疑似消息解析失败时，不再继续跳到后续消息，而是返回 `null` 触发现有 `swipeDown` 重试。
- 根据真机视频继续收窄：首个可解析消息离分割线过远时拒绝当锚点，并使用分割线阶段专用慢速小步滑动。
- 新增无分割线兜底：屏幕不够且分割线找不到时，回到底部按 ListView 消息行倒数第 K 条定位。
- 长按前增加危险区域保护：锚点太靠近顶部/底部时拒绝长按，避免选到旧消息或底栏。
- 点击“多选”改为坐标点击 + 进入多选模式校验 + 最多重试 2 次；校验失败则终止本批并 dump，不再继续“选择到这里”滚动。

## 已修改文件
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageCollector.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageForwarder.kt`
- `CONTEXT_HANDOFF.md`

## 关键决策
- 冻结 `selectTargetGroups()`，不修改选群流程。
- 不修改 `MessageForwarder.kt` 的批次、长按、多选、发送流程。
- 当前修复优先避免漏选第一条未读，遇到疑似消息但解析失败时宁可微调重试。
- 分割线搜索先降速确认逻辑正确性；不改全局 `GestureHelper`，避免影响选群。
- 未读徽章 K 是主依据；分割线是辅助信号，不再作为唯一兜底。
- “多选”未真正生效比漏转更危险，因为会继续扩选旧消息；因此当前策略是失败即停，不再带病执行。

## 未完成事项
- `git diff --check` 已通过。
- 本地 Gradle 编译未完成，当前环境缺 Java Runtime：`Unable to locate a Java Runtime`。
- 需要真机验证：少量未读无分割线、多条未读有分割线、连续小程序卡片三类场景。

## 下一步
- 如需发布，提交 `MessageForwarder.kt`、`MessageCollector.kt` 和 `CONTEXT_HANDOFF.md`，排除未跟踪的 `1.jpg`。
- 真机重点观察日志：`锚点来源`、`锚点在危险区域`、`点击'多选' attempt=`、`已进入消息多选模式`。

## 已知问题
- CI 只能验证编译，不能证明企微无障碍 UI 行为正确。
- `1.jpg` 仍为未跟踪文件，本次不应纳入提交。
