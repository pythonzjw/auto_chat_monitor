# CONTEXT_HANDOFF

## 当前目标
- 提升无分割线场景的消息边界稳定性：屏幕内足够仍按倒数 K；超屏优先分割线；分割线缺失时用“时间行 + 上次成功转发时间”做保守兜底；不恢复跨屏 K 计数。

## 已完成
- 新增 `Storage.saveForwardSuccessAt()` / `loadLastForwardSuccessAt()`，仅保存最近一次全部目标群转发成功的时间戳。
- `MessageForwarder` 接入时间行兜底：分割线找不到后，尝试用时间行定位锚点；失败则拒绝转发，不使用 K 计数兜底。
- `MessageCollector` 新增时间行扫描：先回到底部，再向上找晚于上次成功时间附近的时间行，接受该时间行下方第一条稳定消息作为锚点。
- `TimeParser` 补充 `上午/下午 HH:mm` 解析。
- 选群逻辑、批次逻辑、第二批复定位逻辑未改。

## 已修改文件
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageForwarder.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageCollector.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/Storage.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/TimeParser.kt`
- `CONTEXT_HANDOFF.md`

## 关键决策
- 不再使用文本书签作为边界，避免重复消息误匹配。
- 无分割线 fallback 只信时间行边界；时间行不是绝对未读边界，所以必须晚于上次成功转发时间附近才接受。
- 第一轮没有成功时间时，无分割线且当前屏不足 K 会失败，避免把旧时间块当新消息。
- K 只保留“当前屏足够时倒数第 K 条”的快速路径，不做跨屏累计。

## 验证情况
- `git diff --check` 已通过。
- 本地 `./gradlew assembleDebug` 未运行成功：当前机器缺 Java Runtime，报错 `Unable to locate a Java Runtime`。
- 需要 CI 或装有 JDK 17 的环境验证编译。

## 下一步
- 真机重点观察日志：`分割线未找到，尝试时间行边界兜底`、`[时间行] 命中候选时间行`、`[时间行] 接受时间行`、`时间行边界不可用`。
- 验证三类场景：有分割线、多分钟后无分割线但有时间行、短间隔无时间行。

## 已知问题
- CI 只能验证编译，不能证明企微无障碍 UI 行为正确。
- `1.jpg` 仍为未跟踪文件，本次不应纳入提交。
