# CONTEXT_HANDOFF

## 当前目标
- 未读徽章只作为触发进群信号；进群后等待聊天列表稳定，再按群内分割线/时间行定位第一条新消息并转发到最新消息。

## 已完成
- `MessageForwarder` 改为当前屏不足 `K` 时持续上滑找分割线/时间行边界。
- `MessageCollector.findFirstNewMessageByDivider()` 改为统一扫描：优先分割线，其次屏幕中部时间行；命中后取边界下方第一条稳定消息作为锚点。
- 删除旧时间行兜底的“回到底部重扫”和“上次成功时间判断”，避免第一次运行或无成功时间时拒绝时间行。
- `scrollAndSelectToHere()` 下滑扩选时缓存最后一次下半屏“选择到这里”按钮；到底后当前按钮消失时先 `swipeUp` 小幅回拉再找，仍找不到则点缓存坐标。
- `MessageForwarder` 进入消息多选由单点长按改为锚点消息内多候选点长按：现有坐标、气泡中心、主要文本区域、行安全中心；只有点击“多选”并确认进入多选模式后才继续扩选。
- `scrollAndSelectToHere()` 去掉 20 次下滑硬上限，改为采集运行期间持续下滑；到底判断从文本 signature 改为列表首尾几何位置 + childCount 连续稳定。
- 第 2 批及后续批次从源群底部持续上滑复定位第一条新消息锚点，不再使用 30 次固定上限；任一批失败直接返回失败，不再误报“全部完成”。
- 删除回溯分钟 UI、配置字段、存储字段和旧判断函数；当前转发只按未读数/分割线/时间行定位。
- `enterGroupWaitSeconds` 已加入用户配置保存/加载，启动日志会打印“进群等待: X 秒”。
- 删除 `CollectorService` 中进入源群成功后的重复等待；进群等待只保留在 `Navigator.enterGroup/searchAndEnterGroup` 内执行一次。
- `MessageForwarder.forwardNewMessages()` 不再用未读数 `K` 做精确条数定位；改为先等待聊天列表稳定，再直接查找分割线/时间行锚点。
- `MessageCollector.findFirstNewMessageByDivider()` 找边界时降低上滑步长和节奏；时间行识别从“屏幕中部”扩大到 ListView 可视区，命中时间行但下方消息未稳定露出时会小幅回拉找下面第一条消息。
- 时间行边界现在使用实际时间文本节点 bounds，不再使用整行 bounds；如果同一个 ListView child 同时包含时间和消息，会尝试接受该 child 内时间下方的第一条消息。
- 命中时间行但下方消息仍未稳定露出时，会进入“时间行锁定回拉”：只围绕该时间行中步回拉找下面第一条消息，不再继续普通上滑扫描。
- 采集阶段接受锚点时复用消息长按安全区；底部露出的消息按候选状态微调，不再无限等待。
- 边界定位改为两阶段：先在聊天列表内锁定唯一分割线/时间行，再围绕该边界回拉找下方第一条安全锚点；分割线不再命中后回到普通扫描。
- `scrollAndSelectToHere()` 在底部“选择到这里”持续可见但列表几何无移动时会点击该底部按钮，避免多选后卡在 `observedMovement=false`。
- `scrollAndSelectToHere()` 增加上半屏唯一“选择到这里”兜底：只有列表连续稳定且从未观察到下滑位移时，连续 3 轮可见才点击，避免第二批卡死。
- 选群阶段如果有任一目标群未找到/未勾选，直接返回失败并拒绝点击“确定(N)”，避免 8/9 这类部分发送。
- 边界锁定回拉改为小步无惯性微调；锁定时间行后只追踪同一个时间文本，边界离开可视区时反向小步找回，不再改用顶部首条可见消息。
- 扩选到底时如果当前唯一“选择到这里”在上半屏，不再用 `strict=false` 点击反向按钮；优先回拉找底部按钮或使用最后可信底部坐标。
- 点击“转发/逐条转发”后会等待确认“选择联系人/最近聊天”页面，避免选群页面未加载时误报已进入。
- 边界下方第一条消息定位增加 `SAFE/TOO_LOW/TOO_HIGH/PARSE_PENDING/MISSING` 状态；低位候选按 `bubbleY/safeBottom/delta/adjustStep` 做闭环微调，连续无进展或边界二次丢失会快速失败，避免原地上下抖动几十秒。
- 长按安全下界与转发候选过滤放宽到贴近列表底部但避开底栏，解决时间行在底部时第一条消息已可见却一直被判“不安全”的问题。
- `TimeParser` 补充 `上午/下午 HH:mm` 解析。

## 已修改文件
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageForwarder.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MessageCollector.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/MainActivity.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/Storage.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/CollectorService.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/Config.kt`
- `android/WeworkForwarder/app/src/main/java/com/wework/forwarder/TimeParser.kt`
- `android/WeworkForwarder/app/src/main/res/layout/activity_main.xml`
- `CONTEXT_HANDOFF.md`

## 关键决策
- 不再使用文本书签作为边界，避免重复消息误匹配。
- 无分割线时直接使用扫描过程中出现在 ListView 可视区的时间行，不再依赖历史成功时间。
- 未进入多选前不回到底部；持续向上找边界，只有控件树异常、离开聊天页或列表连续不动才失败。
- 扩选阶段仍保留列表稳定 3 次判定，但不再因 20 次上限半路退出；按钮目标不再只依赖最后一帧，避免滑过头后按钮消失导致失败。
- 多批次转发时，发送完一批后企微回到底部是正常状态；后续批次必须从底部上滑找回原锚点，再执行与第一批一致的选择流程。
- 未读徽章数只触发进群，不再作为转发条数；如果找不到分割线/时间行，宁可失败也不使用 K 计数误转旧消息。
- “找不到多选”按长按坐标不准处理，不改多选按钮选择器；候选点必须来自同一个锚点消息节点并通过安全区过滤，避免全屏乱点。
- 回溯时间已彻底下线，不再作为用户可配置项或消息过滤条件。
- `enterGroupWaitSeconds` 的职责是点击进入群聊后等待页面稳定，只应在导航层执行一次。
- 选群必须全量命中才允许确认发送；宁可失败停住，也不允许漏群后继续发送。
- 找到时间行/分割线后不能丢失精确边界；如果边界滑出屏幕，应先找回边界，而不是拿顶部首条消息兜底。

## 验证情况
- `git diff --check` 已通过。
- 本地 `./gradlew assembleDebug` 未运行成功：当前机器缺 Java Runtime，报错 `Unable to locate a Java Runtime`。
- 需要 CI 或装有 JDK 17 的环境验证编译。

## 下一步
- 真机重点观察日志：`未读徽章触发=`、`聊天列表已稳定`、`持续上滑找分割线/时间行边界`、`[时间行] 命中`、`长按候选`、`scrollSelect i=`、`[复定位] iter=`。
- 验证三类场景：当前屏足够 K、有分割线、无分割线但有时间行、扩选滑到底按钮消失后回拉。

## 已知问题
- CI 只能验证编译，不能证明企微无障碍 UI 行为正确。
- `1.jpg` 仍为未跟踪文件，本次不应纳入提交。
