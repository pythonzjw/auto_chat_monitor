# CONTEXT_HANDOFF

## 当前目标
- 稳定从企微群聊内“分割线/时间行”下方第一条新消息开始多选，并一直扩选到最新消息；未读数只作为触发进群信号。

## 已完成
- 当前正式可用基线曾标记到 `v2.4.48`；问题集中在时间行靠近底部时，第一条新消息已识别但长按点仍在底部不安全区。
- 本轮修改 `MessageCollector.findFirstNewMessageByDivider()`：
  - 命中分割线/时间行并识别到下方候选消息后，立即进入 `messageLocked` 模式。
  - `messageLocked` 使用真实内容片段作为 key 复定位候选消息，不再要求原时间行/分割线继续可见。
  - 候选消息低于长按安全区时只按候选消息单向上移；避免围绕时间行“上滑一下、下滑一下”的抖动。
  - 微调次数从 24 提升到 36，且按 `bubbleY-safeBottom` 自动加大微调步长。
- 本轮修改 `MessageForwarder.buildLongPressCandidates()`：
  - `rowSafeCenter` 只在行高度足够且安全点仍落在原行内时加入，避免把兜底点夹到行外空白区域。

## 已修改文件
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageCollector.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageForwarder.kt`
- `CONTEXT_HANDOFF.md`

## 关键决策
- 看到边界下方第一条候选消息后，后续定位以“消息本身”为准，不再回头找时间行/分割线。
- 仍然不使用 K 计数兜底，避免未读数不准确时误转旧消息。
- 选群逻辑本轮不动。

## 验证情况
- `git diff --check` 已通过。
- 本地 `./gradlew assembleDebug` 未成功：当前机器无 Java Runtime，报错 `Unable to locate a Java Runtime`。
- CI `v2.4.49` 首次失败原因：Kotlin 字符串插值 `$lockedKind可见` 被解析成变量名；已改为 `${lockedKind}可见`。

## 下一步
- 真机重点看日志是否出现：`已锁定候选消息`、`messageLocked candidateState=TOO_LOW`、最终 `messageLocked 候选消息进入可长按区`。
- 若编译需用 CI 或安装 JDK 17 后验证。

## 已知问题
- CI 只能验证编译，不能证明企微无障碍 UI 行为正确。
- `1.jpg` 是未跟踪文件，不应提交。
