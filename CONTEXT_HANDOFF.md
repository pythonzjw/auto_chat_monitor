# CONTEXT_HANDOFF

## 当前目标
- 修复源群锚点选择错误：快团团/小程序复合消息长按点不准，以及 K 兜底把企微系统提示当成未读消息。

## 已完成
- `MessageForwarder.buildLongPressCandidates` 增加卡片相似锚点识别：内容/节点包含“快团团”“小程序”“＠微信”等时按卡片候选处理。
- 增加 `textCluster/textClusterTop/textNodeN` 候选，把复合消息里的多个文本碎片合并成长按区域，避免只按 80x40 小节点。
- `MessageCollector` 增加外部群提示过滤：`此群为外部群，了解更多` 不再被识别为消息行或 K 兜底锚点。
- 保留原有选群计数/蓝勾逻辑，不改选群流程。

## 已修改文件
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageForwarder.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageCollector.kt`
- `CONTEXT_HANDOFF.md`

## 关键决策
- “长按进不了多选”分两类处理：真消息但长按点碎片化 → 增加卡片/文本簇候选；系统提示误作锚点 → 系统消息过滤。
- K 兜底只应计真实消息行，不应把无头像/无气泡的企微提示算入未读消息。

## 验证情况
- `git diff --check` 通过。
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew assembleDebug` 通过。

## 未完成事项
- 待真机验证：出现“此群为外部群，了解更多”时，K=3 不再选它为锚点；快团团消息应出现 `cardNode/cardCenter/textCluster` 等候选并进入多选。
- 待按需提交、打 tag、触发 CI。

## 已知问题
- 未跟踪文件 `1.jpg` 不属于本次改动，不应提交。
