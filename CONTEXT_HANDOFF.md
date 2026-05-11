# CONTEXT_HANDOFF

## 当前目标
- 提升超屏消息边界稳定性：屏幕内足够仍按倒数 K；超屏时持续向上找“以下为新消息”分割线或屏幕中部时间行；不恢复跨屏 K 计数。

## 已完成
- `MessageForwarder` 改为当前屏不足 `K` 时持续上滑找分割线/时间行边界。
- `MessageCollector.findFirstNewMessageByDivider()` 改为统一扫描：优先分割线，其次屏幕中部时间行；命中后取边界下方第一条稳定消息作为锚点。
- 删除旧时间行兜底的“回到底部重扫”和“上次成功时间判断”，避免第一次运行或无成功时间时拒绝时间行。
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
- 无分割线时直接使用扫描过程中出现在屏幕中部安全区的时间行，不再依赖历史成功时间。
- 未进入多选前不回到底部；持续向上找边界，只有控件树异常、离开聊天页或列表连续不动才失败。
- K 只保留“当前屏足够时倒数第 K 条”的快速路径，不做跨屏累计。

## 验证情况
- `git diff --check` 已通过。
- 本地 `./gradlew assembleDebug` 未运行成功：当前机器缺 Java Runtime，报错 `Unable to locate a Java Runtime`。
- 需要 CI 或装有 JDK 17 的环境验证编译。

## 下一步
- 真机重点观察日志：`持续上滑找分割线/时间行`、`[时间行] 屏幕中部候选`、`[时间行] 命中`、`动态扫描未命中且列表无进展`。
- 验证三类场景：当前屏足够 K、有分割线、无分割线但有时间行。

## 已知问题
- CI 只能验证编译，不能证明企微无障碍 UI 行为正确。
- `1.jpg` 仍为未跟踪文件，本次不应纳入提交。
