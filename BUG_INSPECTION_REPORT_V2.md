> ⚠️ **历史审查/修复记录（2026-09）。其中「联网一期 / DeepSeek / API Key / INTERNET 权限」相关条目已于 v2.0.14 整条撤销** —— 见 docs/ARCHITECTURE.md §2.13 的撤销记录。文中登记为「已修复」的 P0-2 / V2-P3-5（Keystore 兜底、加密存储主线程）等条目随 AiCredentialsStore 一起删除，不要再按本文去代码里找它们。
>

# IronHabit（自律健身）App 代码检验报告 · 第二轮（V2）

| 项目 | 内容 |
|---|---|
| 检验对象 | `D:\fitness-app-v204 1`（包名 `com.ironhabit.app`，v204 工作副本） |
| 检验方式 | **只读静态审查**（未修改任何代码） |
| 本轮性质 | 第二轮复查 = ① 对第一轮 50 条结论逐条复核 + ② 四路换角度补查（跨层一致性 / 组件·预设数据·日期边界 / 并发·生命周期·Flow 装配 / 测试套件与 CI） |
| 检验日期 | 2026-09-25 |
| 前序报告 | [BUG_INSPECTION_REPORT.md](BUG_INSPECTION_REPORT.md)（第一轮：3 P1 / 13 P2 / 34 P3） |
| 本次检验 | 仍未在本机运行编译与单测（本机无 Android SDK）；结论均为静态分析 |

---

## 一、本轮总结论

1. **第一轮报告可信：16 条 P1/P2 逐条复核，12 条完全确认、3 条修正（行号/症状细节）、0 条误报。** 唯一的实质性口径修正是反向的——原 P3-2（`moveTo` 无事务）比第一轮描述的**更严重**：它有活跃调用方（`TodayViewModel.kt:423` 挪餐路径），并非"潜在"。
2. **一处"疑似"定案为真**：原 P2-13（深嵌套 JSON 栈溢出）经核实 kotlinx-serialization 的 JSON 深度上限是 **1.9.x 才加入的防护**，本工程用的 **1.7.3 没有**（见 [kotlinx.serialization issue #1594](https://github.com/Kotlin/kotlinx.serialization/issues/1594)）——粘贴约百层嵌套、≤512KB 的 JSON 可使导入路径崩溃。由"疑似"升级为**确定**。
3. **新发现 2 个 P2、8 个 P3**（详见第四节），最重要的是：**两个广播接收器的协程没有异常兜底，而全工程没有任何 CoroutineExceptionHandler**——开机/闹钟路径一旦遇到存储异常会直接杀进程（BootReceiver 场景=开机崩溃循环）。
4. **四路补查确认了大量"干净面"**（跨层一致性 0 缺陷、21 个 UI 组件 0 缺陷、测试套件无假测试、90+ 核心测试与源码逐条吻合），并排除了两个"看似是 bug"的疑点（见第五节）。

### 累计缺陷台账（第一轮 50 条 + 本轮净增 10 条，无一条被撤销）

| 严重度 | 第一轮 | 本轮净增 | **累计** |
|---|---|---|---|
| P0 致命 | 0 | 0 | **0** |
| P1 严重 | 3 | 0 | **3** |
| P2 一般 | 13 | +2 | **15** |
| P3 轻微 | 34 | +8 | **42** |
| **合计** | **50** | **+10** | **60** |

---

## 二、第一轮 P1/P2 复核裁决（16 条）

| 编号 | 裁决 | 说明 |
|---|---|---|
| P1-1 教练上下文取全历史周计划 | ✅ 确认 | SQL 无周过滤、同函数内口径分裂、消费链（AskCoach/CoachInsight/ExplainDiet → RemoteChatPromptBuilder:49）与多周数据可达性全部核实 |
| P1-2 远端建议丢 equipment | ✅ 确认 | 构造处、默认值 `AdviceModels.kt:245`、入库处 `SuggestExercisesUseCase.kt:95`、本地对照 `LocalRuleAdvisor.kt:775`、回落判据 `:832-840` 全链核实 |
| P1-3 训练页错误态粘滞 | ✅ 确认（措辞修正） | 机制成立；但错误分支带「重试」按钮（`TrainScreen.kt:145-146` → `onRetry()` 会清 errorRes），准确说法是"**不随数据恢复自动清除**，需手动重试"，不是"永不清除" |
| P2-1 编辑一餐换餐次撞唯一约束 | ✅ 确认 | 唯一索引、4 餐次全可点、保存链路（TodayViewModel:449-476 → UpsertMealUseCase → MealRepositoryImpl:78）、对照计划编辑的 `error_duplicate_plan` 全部核实 |
| P2-2 一键打卡抹备注/实际值 | ✅ 确认 | `QuickCheckInUseCase.kt:38-53` + `CheckInDao.kt:84-88` 只合并 RPE；触发点 `ExerciseCheckCard.kt:74,162-171` 部分完成时按钮可达 |
| P2-3 自律页错误态粘滞 | ✅ 确认（同上措辞修正） | `DisciplineViewModel.kt:214` 同形；页面有手动重试 |
| P2-4 自律页跨午夜写昨天 | ✅ 确认 | 未接 `TodayClock.epochDay`；`TodayClock.kt:19-27` 的 KDoc 自己就把 DisciplineViewModel 列为 6 份拷贝之一，反向印证 |
| P2-5 mask=0 残行计活跃日 | ✅ 确认 | 四个消费方（observeActiveDaysSince / TrainingDayResolver:46 / heatmapRows / distinctActiveDays）全部中招 |
| P2-6 历史/档案页"今天"冻结 | ⚠️ 修正 | 机制成立，但症状细节有误：热力图与完成率是**实时重算**的，矛盾出现在"列表没今天 vs 图表有今天"；Profile 页离开超 5 秒再回会重算，"无自动恢复"仅对持续停留成立 |
| P2-7 设置/身体数据页错误后卡死 | ✅ 确认 | "只能重启 App"稍重（重进页面可重建 VM），机制成立；**且本轮发现同形问题还存在于另外两页** → 新增 A-2 |
| P2-8 计划预览页无 try/catch | ✅ 确认（行号勘误） | 三处位置应为 `commit` **291-317**、`onApplyProfile` **335-356**、`load` **187-255**（第一轮引的行号偏了约 95-100 行） |
| P2-9 食物库保存 try/finally 无 catch | ✅ 确认（行号勘误） | 正确位置 `FoodLibraryViewModel.kt:195-247`（try 211 / finally 244） |
| P2-10 训练页 fillMaxSize 裁切 | ✅ 确认 | 实际修饰符在 `TrainScreen.kt:212/391/723`（第一轮引的 149/158/166 是分支调用行）；Compose 测量语义经官方文档核实 |
| P2-11 饮食清单+食物库双弹层叠加 | ✅ 确认 | `TodayScreen.kt:329` 与同文件另三处入口（282-286/347-351/354-358）对照成立 |
| P2-12 导入详细错误永不展示 | ✅ 确认 | `BackupViewModel.kt:113` 只看 `getOrNull()`；`ImportDataUseCase.kt:21` 的"原样透传"KDoc 与实际消费链矛盾 |
| P2-13 深嵌套 JSON 栈溢出 | ✅ **定案为真**（行号勘误） | kotlinx-serialization 深度上限 1.9.x 才有，1.7.3 无防护；入口长度闸在 `ExternalPlanDocumentParser.kt:100-103`，`decode` 在 `:667-682` 只 catch 异常类不 catch `Error` |

**P3 抽查 13 条：12 条确认、1 条修正、0 条误报。** 修正条目：

> **[升级] 原 P3-2 `MealItemRepositoryImpl.moveTo` 读改写无事务**：第一轮称"当前无调用方、潜在"，**有误** —— `TodayViewModel.kt:421-424` 的 `onMoveItemToMeal`（饮食条目"挪到另一餐"功能，用户可直接触发的路径）就在调用它。并发挪餐/改份量时存在丢更新与 `sort_order` 撞号竞态（工程内 `MealItemDao.moveToMeal:101` 已有单语句版本未被使用）。维持 P3，但按**活代码**对待。

---

## 三、对第一轮报告的勘误表（V1 → 修正）

| V1 条目 | V1 位置 | 正确位置/表述 |
|---|---|---|
| P2-6 症状 | "热力图今天有格子、下方列表没有今天、完成率还是昨天窗口" | 完成率/热力图实时重算；矛盾 = **列表窗口冻结** vs 图表实时；Profile 页重进即恢复 |
| P2-8 | `PlanPreviewViewModel.kt:196-215/242-253/127-163` | `commit` **291-317**、`onApplyProfile` **335-356**、`load` **187-255** |
| P2-9 | `FoodLibraryViewModel.kt:181-215` | **195-247** |
| P2-10 | `TrainScreen.kt:149/158/166` | 修饰符在 **212（PlanSection）/ 391（LibrarySection）/ 723（HistorySection）** |
| P2-13 | "疑似" | **确定**（1.7.3 无深度防护；长度闸 `ExternalPlanDocumentParser.kt:100-103`，decode `:667-682`） |
| P1-3 / P2-3 | "永不清除" | "不随数据恢复自动清除，需手动点重试" |
| P3-2 | "当前无调用方" | **有活调用方** `TodayViewModel.kt:423`（挪餐路径） |
| P3-17 | `ExternalPlanDocumentParser.kt:183` | **:570** |
| P3-33 | `SettingsScreen.kt:866` | **:856**（SettingsViewModel.kt:388 无误） |
| P3-22 补充 | — | runCatching 变体另有两处：`AiCoachViewModel.kt:332/376`、`TrainViewModel.kt:248/275` |

---

## 四、本轮新发现（2 P2 + 8 P3）

### P2 新增

**V2-P2-1【P2】两个广播接收器的协程无异常兜底，且全工程没有 CoroutineExceptionHandler —— 闹钟/开机路径异常会杀进程（置信度：较高，已人工复核）**
- 位置：`data/notification/ReminderReceiver.kt:55-86`、`data/notification/BootReceiver.kt:47-53`
- 问题：两者都是 `goAsync()` + `applicationScope.launch { try { … } finally { pendingResult.finish() } }`——**只有 finally 没有 catch**；`@ApplicationScope`（`di/AppModule.kt:48-50`）是普通 `CoroutineScope(SupervisorJob() + IO)`，SupervisorJob 只隔离兄弟协程不拦未捕获异常；全工程 grep 无任何 `CoroutineExceptionHandler`/`DefaultUncaughtExceptionHandler`。
- 后果分级：
  - `BootReceiver` → `reminderScheduler.rescheduleAll()` 第一步就是 DataStore 读 + Room 遍历；存储损坏抛 IOException → **每次开机都崩**（开机广播 → 崩 → 下次开机再崩）的崩溃循环；
  - `ReminderReceiver` → 深夜闹钟触发时 `getHabit`/`showReminder`/`scheduleNext` 任一抛错 → "半夜 App 自己崩了"。
- 佐证：`IronHabitApp.kt:62-68、96` 对**同一个** `rescheduleAll()` 的另外两处调用都包了 `runCatching`——接收器正是漏掉的两处。
- 修复方向：两处补 `runCatching`（或给 ApplicationScope 统一加 CoroutineExceptionHandler），约 4 行。

**V2-P2-2【P2】「历史」「动作详情」两页同样是"错误态死锁、无重试入口"——P2-7 的同形问题实际共有 4 页（置信度：确定，已人工复核）**
- 位置：`ui/screens/history/HistoryViewModel.kt:117-124` + `HistoryScreen.kt:64-65`；`ui/screens/exercise/ExerciseDetailViewModel.kt:89-97` + `ExerciseDetailScreen.kt:60-61`
- 问题：`.catch { emit(错误帧) }` 直接挂在 combine 外（无 retryTrigger/flatMapLatest 包裹）→ 流终止后 `dataState` 冻结在错误帧；两页均无 `onRetry`，错误分支是**无按钮**的 `EmptyState`。对照 Today/Train/Discipline/Profile/TrainingStats 五页的"retryTrigger.flatMapLatest{…catch}+重试按钮"标准，这是两页缺口。
- 修复方向：照抄本工程 Train/Profile 的 retryTrigger 模式（或至少给 EmptyState 加重试按钮）。

### P3 新增

**V2-P3-1【P3】foods.json 三条坚果（核桃仁/杏仁/腰果）缺 `PEANUT` 过敏原标签 —— 外部导入安全闸漏放（置信度：较高，已人工复核）**
- 位置：`app/src/main/assets/foods.json`（核桃仁 :1026 / 杏仁 :1038 / 腰果 :1047，三者 `"dietaryTags": []`）
- 问题：工程对 `PEANUT` 的自定义口径是"花生、核桃、杏仁等坚果类"（`BuiltInMealTemplates.kt:17`），模板侧的核桃/杏仁都标了，食物库这侧三条漏标。消费端 `ExternalPlanDocumentParser.kt:350-355` 用 `food.dietaryTags ∩ 忌口` 做**安全闸**（"命中就整条挡"）——用户勾了忌口"花生/坚果"后，外导计划里的杏仁/核桃/腰果行不会被拦。
- 修复方向：JSON 补 3 个标签（纯数据修复）。

**V2-P3-2【P3】导入 v1/v2 老备份会把本机主题/单位/提醒时间静默重置为默认值（置信度：疑似）**
- 位置：`data/repository/BackupRepositoryImpl.kt:207-210`
- 问题：v1/v2 备份没有 `settings` 快照（v3 引入），解码得默认值 `reminderEnabled=true, reminderHour=20, themeMode=SYSTEM`；`applySettings` 不做版本闸直接写回 → 老备份导入后本地"提醒已关/自定义 07:30/深色"被洗掉。同文件对 meals（P0-1）、档案、createdAt（D16）、trainingDaysPerWeek（B-6）都做了版本闸，唯这三项例外——是有意兼容（注释自辩）还是口径疏漏，建议维护者裁决。

**V2-P3-3【P3】AiCoachViewModel / FoodLibraryViewModel 的 init 裸 collect Room/DataStore 流，无 catch（置信度：较高）**
- 位置：`ui/screens/ai/AiCoachViewModel.kt:183-198`、`ui/screens/food/FoodLibraryViewModel.kt:89-106`（另 `onDeactivate/onActivate` :255-263 裸 launch 写库）
- 问题：全部 10 个 stateIn 型 VM 的数据流都有 `.catch`，唯独这两处 `viewModelScope.launch { flow.collect }` 直连——DB/存储异常即未捕获协程异常崩溃。与 P2-8/P2-9 同族的防线缺口。

**V2-P3-4【P3】设置页器械/伤病/忌口三处勾选是"读-改-写"，并发连点可丢更新（置信度：较高）**
- 位置：`ui/screens/settings/SettingsViewModel.kt:286-299、311-317`
- 问题：三处都是 `persist { val current = profile().first()…; toggle(current, item) }`，注释自称"避免连点丢改动"，实际无互斥——两次快速点击交错成 读A-读B-写A-写B，A 的勾选丢失，用户看到"点了没勾上"。

**V2-P3-5【P3】Keystore/EncryptedSharedPreferences 初始化在主线程同步执行（置信度：行为确定，判级疑似）**
- 位置：`ui/screens/settings/SettingsViewModel.kt:134,145-152`、`ui/screens/ai/AiCoachViewModel.kt:194,313-315`（底层 `AiCredentialsStore.kt:152-163`）
- 问题：`MasterKey.Builder().build()` + `EncryptedSharedPreferences.create` 是 Keystore 操作（KDoc 自述"贵"），在主线程 init/ON_RESUME 反复调用——慢设备冷启动进设置/AI 页有一次可感知卡顿，极端 ANR 风险。P0-2 只修了"抛异常崩溃"，未修阻塞。

**V2-P3-6【P3】AddEditPlanViewModel 的 `newRowWeek` 构造时求值一次——TodayClock KDoc 点名铲掉的那类固化又出现一处（置信度：疑似）**
- 位置：`ui/screens/plan/AddEditPlanViewModel.kt:105-110`（消费 `:262`）
- 问题：表单 VM 在后台栈跨周一 00:00 存活后，保存的行落进**上一周**（TrainViewModel 同型问题当年已修并写进了 TodayClock 的 KDoc）。

**V2-P3-7【P3】「训练」页日视图的"周"在重订阅间冻结，展示与写入口径可分裂（置信度：疑似）**
- 位置：`ui/screens/train/TrainViewModel.kt:82-84` + `data/repository/PlanRepositoryImpl.kt:40-41`（承接并升级原 P3-3：本轮确认其有活消费者）
- 问题：用户手动选过星期后，跨午夜换天不触发重订阅 → 日视图仍展示**上一周**该星期的排课；而同屏写入走 `todayClock.weekStartEpochDay()` 实时值——"看到上周三的课，点 + 却写进本周三"。

**V2-P3-8【P3】一次性事件（Snackbar/导航）全部用 StateFlow 可空字段承载，快速连发会覆盖/去重丢失（置信度：行为确定，判级疑似——设计取舍）**
- 位置：全部 17 个 ViewModel（grep 证实无一处 Channel/SharedFlow）
- 问题：连点两个动作时第一次的 `msg_checkin_done` 被第二次覆盖；两次相同写入之间 UI 未消费时第二条无提示。纯 UX 损失，不错写数据；若在意可改 Channel。

**V2-P3-9【P3】约 7 个测试类用 `Clock.System` 快照 `today`，UTC 午夜前后跑 CI 有偶发 flake 窗口（置信度：机制确定，概率极低）**
- 位置：`app/src/test/.../CheckInRpeUpsertTest.kt:40-45`、`StreakDirtyDataTest.kt:37-40`、`TodayViewModelMealEditTest.kt:91-93`、`TodayViewModelMealItemTest.kt:92,179`、`AddEditPlanViewModelWeekScopeTest.kt:60,102,110`、`AddEditHabitViewModelTest.kt:84` 等
- 问题：`today` 在类初始化时求值一次、测试体内再次求值，两次跨午夜即偶发红。改为注入固定时钟即可消除。

---

## 五、本轮新验证的"干净面"与排除的疑点

### 5.1 跨层一致性（0 缺陷）
672 个 `R.string.*` 引用零缺失、多参占位符与实参类型逐一对上（含乱序占位符 `%2$d/%1$d`）；5 Tab + 10 二级路由无死链、参数类型与 SavedStateHandle 全吻合；11 个仓库接口绑定齐全、DI 绑定图静态可闭合、Qualifier 无混用；聚合 DTO 列别名与 `AS` 全部对应；两条 CI workflow 的路径/Secret/白名单与实际闭环；FileProvider authority、backup 规则三处 exclude 与代码一致。

**排除的两个高危疑点（避免以后反复怀疑）：**
1. **迁移里 `ADD COLUMN ... DEFAULT x` 而实体未声明 `@ColumnInfo(defaultValue)` —— 安全。** 反编译 Gradle 缓存中的 `room-runtime-2.6.1.jar` 求证：`TableInfo$Column.equals` 仅当实体侧声明了 defaultValue 时才比对默认值（字节码级证据）。建议后续升级 Room 版本时加一条迁移单测钉住。
2. **备份导入清表依赖 FK CASCADE —— 正确。** 同一事务内父表级联后紧跟子表显式清空+全量重灌，整体可回滚。

### 5.2 UI 组件 / 预设数据 / 日期边界（除 V2-P3-1 外 0 缺陷）
- 21 个组件全部核对：TrendChart/CategoryPieChart/HeatmapGrid/完成环/DietTotalsBar 的除零、负值、NaN、越界、撞色全部有守卫；`PlanGoalSpec` 实现与单测逐条一致；掩码位运算（`(mask shr index) and 1`）正确。
- 预设数据全量校验：51 动作 + 127 食物零重名、枚举全部在词表内、sets/reps 范围合法、kcal 与 4/4/9 系数偏差 ≤ max(8%, 15%)、份量换算合理。
- 日期边界：`weekStartMon1` 对负 epochDay/年界/闰 2/29 正确；日期运算全走 `LocalDate` 不受 DST 影响；`StreakCalculator` 对空集/全集/非法位回落"每天应做"且有测试锁定。
- 模型/备份载荷：`maskFromCount(31)` 的文档化溢出正确；BackupPayload v1→v5 新字段全带默认值 + `ignoreUnknownKeys`，旧→新解码安全；`schemaVersion > CURRENT` 有拦截。

### 5.3 并发 / Flow 装配横向比对（除上述新发现外 0 缺陷）
10 个 stateIn VM 统一 `WhileSubscribed(5000)`，无 Eagerly 后台空转；全部 Screen 用 `collectAsStateWithLifecycle`；`TodayClock` 轮询是内存级、随 VM 取消；带 retryTrigger 的 VM 的 `.catch` 都在 flatMapLatest 内侧（重试可用）；`PlanPreviewHolder` 写入全部线程封闭于主线程；domain 层取消语义正确。

### 5.4 测试套件（95 文件）
- **无假测试**：无零断言、无恒真断言、无吞异常的 catch；两个扫描式契约测试（InputLimits 调用点、占位符）自带防空转守卫。
- **15 个核心测试与主源码逐条核对全部一致**（Streak/DateUtils/SetMask/BackupRules/RepeatWeekly/StreakCursor/PlanDraftProjector/WeekResolver/DateCursor/RpeUpsert 等），全部星期/DST 锚点验算正确；`ReminderTriggerTimeTest` 是刻意利用 DST 而非踩坑；两个对抗测试（P1/P2Adversarial）专门写了反证用例防自身空转。
- 唯一缺口即 V2-P3-9（时钟快照 flake 窗口）。

---

## 六、累计修复优先级（合并两轮）

1. **P2 新增两处最先修**（改动极小）：V2-P2-1 两个接收器补 `runCatching`（约 4 行，消除开机崩溃循环风险）；V2-P2-2 History/ExerciseDetail 照抄 retryTrigger 模式。
2. **三个 P1**（第一轮已定位，均为小改动）：P1-1 换 `observeEffectivePlanForWeek`、P1-2 补 `equipment = candidate.equipment`、P1-3 merge 清 errorRes（顺带修 P2-3）。
3. **数据正确性 P2**：P2-1 餐次槽位预检、P2-2 打卡合并条件扩展、P2-4 Discipline 接 TodayClock、P2-5 mask=0 删行/查询过滤。
4. **防线补齐**：P2-8（行号见勘误表）、P2-9、V2-P3-3、P2-12（透传导入错误 message）。
5. **安全数据**：V2-P3-1 补 3 个 JSON 标签（外部导入过敏原闸门）。
6. **上架前**：P3-34 删 `USE_EXACT_ALARM`（Play 政策）。
7. 其余 P3 按迭代消化；V2-P3-2（v1/v2 备份重置设置）与 V2-P3-4/V2-P3-8（是否算缺陷属设计裁量）建议维护者先裁决口径。

---

## 七、检验局限性

- 纯静态审查，未运行编译/单测/真机（本机无 Android SDK）；建议 CI 跑 `./gradlew testDebugUnitTest` 与 `connectedDebugAndroidTest` 比对（工程含 95 个单测文件 + 3 个 androidTest）。
- `Room 2.6.1 迁移默认值安全`的结论来自对 Gradle 缓存 jar 的字节码反编译；升级 Room 版本后该结论需重验。
- V2-P3-2 / V2-P3-4 / V2-P3-8 的"是否算缺陷"存在产品口径裁量，已如实标注疑似。
- 第一轮 P2-8 等条目的行号偏差已在第三节勘误；引用 V1 行号时以本表为准。

## 参考资料

- [kotlinx.serialization Issue #1594 — StackOverflowError on deeply nested JSON（深度上限 1.9.x 才加入）](https://github.com/Kotlin/kotlinx.serialization/issues/1594)
- [Jetpack Compose — Modifiers（Column weight 测量语义）](https://developer.android.com/develop/ui/compose/modifiers)
- [kotlinx.serialization JSON API](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json/)

*（V2 报告生成：ZCode 静态检验 · 第一轮结论逐条复核 + 四路换角度补查 · 全部 P2 级新发现经人工回源码验证）*
