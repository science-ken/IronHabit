# IronHabit（自律健身）App 代码检验报告

| 项目 | 内容 |
|---|---|
| 检验对象 | `D:\fitness-app-v204 1`（包名 `com.ironhabit.app`，v204 工作副本） |
| 检验方式 | **只读静态代码审查**（未修改任何代码） |
| 检验范围 | `app/src/main` 全部 239 个 Kotlin 文件（约 3.2 万行）+ AndroidManifest + 构建脚本 + 内置数据（assets/foods.json 等），逐文件阅读 |
| 检验日期 | 2026-09-25 |
| 审查方法 | 6 路并行深度审查（Room 数据层 / 仓库·通知·偏好层 / Domain 用例与工具 / AI·饮食·领域模型 / UI 前半 / UI 后半与基础设施），关键发现逐条人工复核源码验证 |
| 本次检验 | 未在本机运行编译与单测（本机无 Android SDK，本工程设计为云端 CI 构建）；结论均为静态分析 |

---

## 一、总体结论

**未发现 P0 级（必然崩溃 / 必然损坏数据）缺陷。发现 3 个 P1 严重缺陷、13 个 P2 一般缺陷、34 个 P3 轻微缺陷，合计 50 个。**

该代码库明显经历过至少 5 轮自查（注释中大量 P0-x / B-x / D-x 历史修复编号）：数据库迁移、事务原子性、备份恢复顺序、图表除零、闹钟权限降级、通知权限流程、DataStore 单例、proguard 保留规则等高危项均已正确处理。本次发现的问题集中在三类：

1. **跨屏数据口径不一致**（教练上下文取了全历史计划、历史/档案页"今天"冻结）；
2. **错误处理防线缺口**（两处"错误态粘滞"、四处挂起调用无 try/catch、导入详细错误被吞）；
3. **跨午夜 / 跨周的时间游标失效**（自律页、历史页、仓库层 Flow 构造时求值）。

---

## 二、缺陷统计

| 严重度 | 数量 | 判定口径 |
|---|---|---|
| P0 致命 | **0** | 必然崩溃或必然损坏数据 |
| P1 严重 | **3** | 主功能路径错误行为 / 数据口径错误 |
| P2 一般 | **13** | 特定条件下功能失败、崩溃或数据污染 |
| P3 轻微 | **34** | 边缘场景、提示失真、防御缺失 |
| **合计** | **50** | |

置信度标注：**确定** = 代码链路完整核实；**较高** = 机制核实、触发依赖特定条件；**疑似** = 行为确定但触发条件或"是否算缺陷"存在设计裁量空间。

---

## 三、P1 严重缺陷（3 个，建议优先修复）

### P1-1 「AI 教练」把全部历史周的计划当成"本周计划"喂给模型
- **位置**：`app/src/main/java/com/ironhabit/app/domain/usecase/BuildCoachContextUseCase.kt:94`
- **置信度**：确定（SQL 与消费链路均已核实）
- **问题**：构建教练上下文时，"本周训练计划摘要"用 `planRepository.observeAll().first()` 取数。`WeekPlanDao.observeAll` 的 SQL 是 `SELECT * FROM week_plans WHERE is_active = 1`（`WeekPlanDao.kt:67`），**不带周过滤**——P3 起计划按周存放（`week_start_epoch_day`），只要用户生成/复制过第二周计划，`weeklyPlan` 就会混入：同一动作同一天多条（模板行 + 各周专属行）、早已过期的旧周排课、已删动作的空名行。这些经 `AskCoachUseCase` / `CoachInsightUseCase` / `ExplainDietUseCase` 序列化进 DeepSeek 提示词，AI 会基于一份重复、过期的"计划"给建议。
- **佐证**：同一函数内 streak 的"应做日"却正确使用了 `observeEffectivePlanForWeek(currentWeekStart)`（122-128 行），两份取数在同函数内口径分裂；字段文档也写明 `weeklyPlan` 是"本周训练计划摘要行"。
- **修复方向**：94 行改用 `observeEffectivePlanForWeek(currentWeekStart)`（需把 currentWeekStart 的计算上移），与同函数 streak 取数对齐。

### P1-2 远端 AI 补充动作丢失 `equipment` 标注，器械约束静默退化
- **位置**：`app/src/main/java/com/ironhabit/app/domain/ai/remote/RemoteLlmAdvisor.kt:133-143`
- **置信度**：确定（构造处、默认值、入库处三段均核实）
- **问题**：`parseSuggestionsJson` 构造 `ExerciseSuggestion` 时未传 `equipment`，落默认值 `emptyList()`（`AdviceModels.kt:245`）。而 `SuggestExercisesUseCase.adopt`（`SuggestExercisesUseCase.kt:95`）直接把 `suggestion.equipment` 写入动作库。候选池里需 MACHINE/DUMBBELL 的动作（坐姿提踵、哑铃肩上推举、椭圆机稳态等）经**远端 AI 路径**收入后器械标注为空，`LocalRuleAdvisor.equipmentAllowed` 对未标注行只能按粗粒度分类放行——只有哑铃的用户会被排入需要器械的动作。本地路径 `LocalRuleAdvisor.toSuggestion` 正确传了 `equipment`，两条路径行为分叉。
- **修复方向**：构造处补 `equipment = candidate.equipment`（一行）。

### P1-3 「训练」页错误态粘滞：一次瞬时失败把整页打成错误且永不清除
- **位置**：`app/src/main/java/com/ironhabit/app/ui/screens/train/TrainViewModel.kt:322`
- **置信度**：确定（已复核 merge 实现；对照 TodayViewModel 的正确写法）
- **问题**：`merge()` 写 `errorRes = data.errorRes ?: local.errorRes`——数据流成功（`data.errorRes == null`）时仍保留本地旧错误。设置 `errorRes` 的三条通道里 `onAdoptSuggestion` 内部是联网 completion（远端为阻塞 HTTP），断网/抖动极易触发；一旦置位，后续任何 Room 重发射都把错误原样续回，`TrainScreen.kt:141-147` 的 `errorRes != null` 分支接管全屏，计划/动作库/历史三分段全部被隐藏。对照 `TodayViewModel.applyData`（`TodayViewModel.kt:786`）在成功路径显式 `errorRes = null`，证明这是 Train/Discipline 两处 merge 的实现疏漏（Discipline 同病，见 P2-3）。
- **修复方向**：merge 改为 `errorRes = data.errorRes`（与 TodayViewModel 对齐）。

---

## 四、P2 一般缺陷（13 个）

### 数据与业务逻辑

**P2-1 编辑一餐切换"餐次"必然撞唯一约束，报错含糊**（置信度：较高）
- `data/repository/MealRepositoryImpl.kt:78` + `data/local/dao/MealDao.kt:166-180`：`upsertUser` 命中 byId 分支直接整行 update，不检查目标 `(date_epoch_day, meal_type)` 槽位是否已被另一行（含软删行）占用；而 `MealEditSheet.kt:112-116` 把 4 个餐次全部渲染为可点选。AI 生成一天通常 4 餐齐全，"编辑早餐 → 改成午餐 → 保存"100% 抛 `SQLiteConstraintException`，被 `TodayViewModel` 吞成 `error_generic`。对比：计划编辑有专门文案 `error_duplicate_plan`，餐次编辑缺同等处理。

**P2-2 「一键打卡」静默抹掉已录的备注、实际次数、实际重量**（置信度：较高）
- `domain/usecase/QuickCheckInUseCase.kt:38-53` + `data/local/dao/CheckInDao.kt:76-92`：`CheckInDao.upsert` 命中已有行时只合并 RPE，`notes / completedReps / weightKg / durationMinutes` 都按入参整列覆盖。用户先详细打卡勾了 2/3 组并写备注（条目未完成，一键打卡按钮仍可见），再点一键打卡 → 备注清空、实际 90kg 被重写成计划目标值。`BackfillCheckInUseCase.kt:31-45` 同理。修复方向：upsert 合并条件扩到"入参为 null 的字段沿用旧行"。

**P2-3 「自律」页粘滞错误态**（置信度：确定）
- `ui/screens/discipline/DisciplineViewModel.kt:212-217`：与 P1-3 完全同形（`errorRes = data.errorRes ?: local.errorRes`），`DisciplineScreen.kt:88-95` 全屏接管。

**P2-4 「自律」页跨午夜后打卡写进昨天**（置信度：较高）
- `ui/screens/discipline/DisciplineViewModel.kt:72,143`：`todayEpochDay()` 只在习惯列表发射时求值一次，App 跨 00:00 存活后无人写库则日期冻结；此时勾选习惯经 `DisciplineScreen` 的 `uiState.dateEpochDay` 写库，streak 与热力图被污染到昨天。这正是 `TodayClock.kt:19-27` KDoc 点名、Today/Train 两页已用轮询修掉的那类 bug，唯独 Discipline 页漏接。

**P2-5 取消全部已勾组后，残留 `mask=0` 行仍被计为"训练活跃日"**（置信度：较高）
- `domain/usecase/ToggleSetUseCase.kt:23-25`（根因 `CheckInDao.kt:133-167`）：取消最后一组只 XOR 置 0 不删行；而 `CheckInDao.observeActiveDaysSince`（`SELECT DISTINCT date_epoch_day`，无 `completed_sets > 0` 过滤）让该日仍进 streak、`TrainingDayResolver` 仍按训练日算热量、热力图仍点亮——同屏出现"条目未完成但 streak +1"的自相矛盾。

**P2-6 历史页 / 档案页"今天"冻结，跨天后界面自相矛盾**（置信度：机制确定）
- `ui/screens/history/HistoryViewModel.kt:92`、`ui/screens/profile/ProfileViewModel.kt:73-74`：统计窗口 `[T0-179, T0]` 在订阅期求值一次后冻结，跨天存活后新打卡落在窗口外，出现"热力图今天有格子、列表里没有今天、完成率还是昨天窗口"的矛盾界面，无自动恢复路径。

**P2-7 设置 / 身体数据页数据流一旦抛异常，页面永久卡死在错误态**（置信度：机制确定）
- `ui/screens/settings/SettingsViewModel.kt:97-133`、`ui/screens/bodymetrics/BodyMetricsViewModel.kt:104-117`：`combine(...).catch { emit(错误帧) }` 后流终止，init 里的 collect 结束，`_uiState` 从此不再更新；错误分支又是**无重试按钮**的 EmptyState，用户只能重启 App。

### 崩溃防线缺口

**P2-8 计划预览页采纳/落库路径完全无 try/catch**（置信度：较高）
- `ui/screens/planpreview/PlanPreviewViewModel.kt:196-215`（`commit` → `generateTrainingPlan.commit`）、`:242-253`（`onApplyProfile` → DataStore 逐字段写）、`:127-163`（`load` → DB 读）：DB/IO 异常会以未捕获协程异常直接崩溃，且 `busy=true` 来不及复位。本工程其余写路径均包 `catch (Throwable)`（部分还专门处理 `SQLiteConstraintException`），唯独预览页缺口。

**P2-9 食物库保存路径 try/finally 无 catch**（置信度：较高）
- `ui/screens/food/FoodLibraryViewModel.kt:181-215`：`onSave` 只有 `finally` 复位按钮，`nameExists/getFood/upsert` 的异常直接冒泡到 `FoodLibrarySheet.kt:508` 的 `scope.launch`（无 try/catch）→ 崩溃。触发：唯一索引 TOCTOU 冲突（两处弹层共享同一 VM 实例）或 DB IO 错误。

### UI 行为

**P2-10 「训练」页内容区用 `fillMaxSize()` 而非 `weight(1f)`，底部约 60-80dp 内容永远滚不出来**（置信度：较高）
- `ui/screens/train/TrainScreen.kt:149/158/166`（Column 内常驻分段条之下三个分段的内容容器）。对照 `TodayScreen.kt:146-149` 同场景正确用了 `weight(1f)`。

**P2-11 「饮食」清单弹窗里点"食物库"不关前一层，两个 ModalBottomSheet 叠加**（置信度：疑似）
- `ui/screens/today/TodayScreen.kt:329`：同文件另外三个二级弹层入口都先 `sheetTarget = null`（282-286 行注释明言"两个 ModalBottomSheet 不能叠"），唯独此处漏改。

**P2-12 备份导入的详细错误信息永远不会展示**（置信度：确定，已复核）
- `ui/screens/settings/BackupViewModel.kt:113,117-124`：`importSnackbarRes(result.getOrNull())` 只看成功与否；`ImportDataUseCase` 精心构造的"备份文件过大（超过 64 MB）…"、"无法读取所选文件…"（`ImportDataUseCase.kt:33-38`）被降级成统一的"文件格式不正确"——选错文件的用户被误导。用例层注释明言"失败原因原样透传给 UI"，实际是死代码。

### 健壮性（依赖库行为）

**P2-13 外部计划粘贴解析对深嵌套 JSON 无深度防护，最坏情况 StackOverflowError 崩溃**（置信度：**疑似**）
- `domain/ai/external/ExternalPlanDocumentParser.kt:266-275`：入口只做 512KB 长度闸，`decode` 只 catch `SerializationException`/`IllegalArgumentException`，接不住 `Error`。kotlinx.serialization 历史上对深嵌套输入存在递归下降栈溢出问题（检索提示 1.6.x 起可能已加深度上限，**未能离线核实本工程所用 1.7.3 的实际行为**）；若已有防护则该输入抛 SerializationException 被正常接住。同文件模式也在 `RemoteLlmAdvisor.decodeStrict`（输入源为 DeepSeek 响应，风险低一档）。修复方向：解析前自查嵌套深度（如 ≤64 层），成本一行。

---

## 五、P3 轻微缺陷（34 个，简表）

### 数据 / 仓库 / 通知（8）
| # | 位置 | 问题 | 置信度 |
|---|---|---|---|
| 1 | `data/local/entity/Converters.kt:31-60` | 4 个枚举 TypeConverter 对未知值回落 null，映射到非空字段时整表查询 NPE（FoodSource 同类问题已修，这 4 处未修完） | 较高 |
| 2 | `data/repository/MealItemRepositoryImpl.kt:53-59` | `moveTo` 三步读改写无事务，并发挪餐丢更新/sort_order 撞号（当前无调用方，潜在） | 确定 |
| 3 | `data/repository/PlanRepositoryImpl.kt:40-42` | `observePlansForDay` 在 Flow 构造时捕获 `currentWeekStart()`，跨周后不刷新 | 疑似 |
| 4 | `data/repository/PlanRepositoryImpl.kt:212-218` | 「复制到下周」会把目标周软删槽位复活，且确认框计数与实际波及不一致 | 疑似 |
| 5 | `data/notification/ReminderReceiver.kt:66-82` | 习惯闹钟自续期不查 `habit.isActive`，删除与触发的竞态窗口内产生孤儿闹钟（冷启动自愈） | 疑似 |
| 6 | `data/repository/BackupRepositoryImpl.kt:108-111` | 导出时对同一 DataStore 三个 Flow 各自 `first()`，非原子快照 | 确定 |
| 7 | `data/mapper/FoodMapper.kt:79-81` | `normalizeServings` 注释称单位比较忽略大小写，实现区分大小写（当前全中文单位，无实害） | 确定 |
| 8 | `data/preferences/AiCredentialsStore.kt:134-145` | `resetStorage` 对 cached/openFailed 的复位不在锁内，与 `prefs()` 打开流程竞态 | 疑似 |

### Domain 用例（6）
| # | 位置 | 问题 | 置信度 |
|---|---|---|---|
| 9 | `domain/usecase/QuickCheckInUseCase.kt:53`（`DetailedCheckInUseCase.kt:102` 同） | upsert 幂等空更新时仍 `bumpUsage`，重复点击使动作使用次数虚高 | 较高 |
| 10 | `domain/usecase/PlanPreviewHolder.kt:53-68` | 部分采纳后快照滞留单例，重进预览页把已采纳的天再当草案展示 | 疑似 |
| 11 | `domain/usecase/BuildExternalCoachPromptUseCase.kt:93-102` | 某天只剩软删行时提示词写"沿用模板未单独排"，与投影器的允许写入判定不一致，AI 系统性少排已腾出的天 | 较高 |
| 12 | `domain/usecase/ChangeMealItemPortionUseCase.kt:52-57` | 按克数改份量时把已有 servingUnit/servingCount 清 null，"1.5碗"标签丢失 | 疑似 |
| 13 | `domain/usecase/UpsertMealUseCase.kt:28-36` | 注释声称"至少保留一个非空条目"，代码未强制，全空输入落一条空餐 | 疑似 |
| 14 | `domain/usecase/AskCoachUseCase.kt:78-80` | 空问题归类为 `Failed(REMOTE_ERROR)`，UI 按"联网失败可重试"误导 | 确定 |

### AI / 饮食（7）
| # | 位置 | 问题 | 置信度 |
|---|---|---|---|
| 15 | `domain/ai/DelegatingPlanAdvisor.kt:51-61` | 单例上 `lastUsedSource`/`lastFallbackReason` 非原子成对更新，并发调用 UI 提示可能错配 | 疑似 |
| 16 | `domain/ai/remote/DeepSeekClient.kt:112-113` | 非流式 30s read timeout + max_tokens=2000，长回复场景频繁假性失败回落本地 | 疑似 |
| 17 | `domain/ai/remote/RemoteLlmAdvisor.kt:84`、`domain/ai/external/ExternalPlanDocumentParser.kt:183` | AI 路径 `targetWeightKg` 只过滤 `>0` 无上限钳制，与用户输入路径（MAX_WEIGHT_KG=500）口径不对称 | 确定 |
| 18 | `domain/ai/LocalRuleAdvisor.kt:562-566` | 渐进超负荷 `previousWeight + weightStepKg` 无上限，长期使用可无限增长 | 确定 |
| 19 | `domain/ai/remote/RemoteLlmAdvisor.kt:165-176`、`RemoteChatPromptBuilder.kt:178-189` | `stripCodeFence` 处理不了四反引号围栏，合法文档被整份拒收 | 较高 |
| 20 | `domain/diet/DietPlanGenerator.kt:207` | `floorMod(epochDay + ordinal, templates.size)` 空列表除零（生产路径模板非空，不可达，防御缺失） | 确定 |
| 21 | `domain/ai/external/ExternalPlanDocumentParser.kt:93-104` | 重复 `dayOfWeek` 的 day 对象不合并不提示，跨对象同日去重被绕过 | 疑似 |

### UI 前半（8）
| # | 位置 | 问题 | 置信度 |
|---|---|---|---|
| 22 | `TodayViewModel.kt:703-725` 等 6 处（Train/Discipline/AddEditHabit/AddEditPlan 同） | 通用 `catch (Throwable)` 吞掉 `CancellationException`，与文件内自己"必须重抛"的约定相悖 | 确定 |
| 23 | `TodayViewModel.kt:296-307` + `TodayScreen.kt:394` | 习惯勾选忽略 `done` 参数按陈旧状态翻转，快速双击净效果错 | 较高 |
| 24 | `TodayScreen.kt:109-117`（Train/Discipline 同） | `LaunchedEffect(snackbarText)` 以文案为 key，同文案连发第二条被静默丢弃 | 较高 |
| 25 | `TrainScreen.kt:105-107` + `TrainViewModel.kt:244-265` | 每次切进「动作库」分段都重发一次建议请求（远端=一次 HTTP），无已加载记忆 | 疑似 |
| 26 | `ui/screens/ai/ExternalImportViewModel.kt:197-198` | `parse()` 的 launch 内 DB 读+投影无 try/catch，非预期异常崩溃 | 疑似 |
| 27 | `ui/screens/ai/ImportPlanSheet.kt:61,115-121` | 切换目标周后 `copied` 状态不重置，"已复制"残留误导 | 确定 |
| 28 | `ui/screens/ai/AiCoachViewModel.kt:279-287,457-464` | 导出数据包失败写 `error_save_failed`，点"重试"走的是上次动作（重新生成计划/饮食）而非导出 | 确定 |
| 29 | `ui/screens/planpreview/PlanPreviewViewModel.kt:124-125` | 进程重建后 `holder.peek()==null` 直接 return，页面卡在空态无引导 | 较高 |

### UI 后半 / 基础设施（5）
| # | 位置 | 问题 | 置信度 |
|---|---|---|---|
| 30 | `data/repository/BackupRepositoryImpl.kt:130` | `import()` 整体 `runCatching` 吞 `CancellationException`，破坏 UseCase 层专门写的取消透传约定 | 确定 |
| 31 | `ui/screens/settings/SettingsScreen.kt:650-657,692-697` | 档案数字逐键提交+越界钳制，不完整输入被静默写成错误值（如敲"1"想输"15"，落库 14） | 行为确定 |
| 32 | `MainActivity.kt:71-75` | 首次通知权限流程中 `markNotificationPermissionAsked()` 的 DataStore 写无兜底，写失败=冷启动崩溃循环（触发极端） | 疑似 |
| 33 | `ui/screens/settings/SettingsScreen.kt:866`、`SettingsViewModel.kt:388` | `"%02d:%02d".format()` 用默认 Locale，非 ASCII 数字地区提醒时间显示为当地数字（纯显示问题） | 确定 |
| 34 | `AndroidManifest.xml:16-17` | 同时声明 `SCHEDULE_EXACT_ALARM` 与 `USE_EXACT_ALARM`，后者按 Google Play 政策仅限闹钟/日历类应用，上架审核会被拦（本机运行无碍，代码降级逻辑健全，删一行即可） | 确定 |

---

## 六、已重点排查、确认无问题的关键面（正面结论）

以下高危项经逐条核实**无缺陷**，可作为回归基线：

- **数据库迁移**：8 条迁移（v1→v9）DDL 与 `app/schemas/1~9.json` 逐字一致、全部注册、无版本跳变、无破坏性回退；minSdk 24 红线遵守。
- **事务原子性**：逐组勾选/习惯勾选的读改写已下沉 DAO `@Transaction`；备份恢复在单事务内按正确外键顺序"先子后父清空、先父后子重灌"；周计划复制/生成/饮食 upsert 均正确包 `withTransaction`。
- **备份导入导出**：URI 一次性读权限够用；UTF-8 读写一致；64MB 上限读取正确；v1–v5 版本分支（饮食四表替换、档案覆盖规则）自洽；FileProvider/分享 intent 异常已兜。
- **闹钟与通知**：PendingIntent 全部 FLAG_IMMUTABLE、requestCode 分槽无冲突；API 31+ 精确闹钟降级、API 33+ POST_NOTIFICATIONS 运行时权限流程完整；BootReceiver 注册与触发广播齐全；DST 用 `LocalDateTime.toInstant` 处理正确。
- **日期与算法核心**：`DateUtils.weekStartMon1`（含负 epochDay、1970 前一周）、`StreakCalculator`（未来日过滤、宽限、gap≤7 扫描）、组掩码 `maskFromCount(31)`/`mergedMask` 边界均验证正确；时间全部走注入 Clock/TimeZone，无硬编码时区。
- **营养计算**：Mifflin-St Jeor 公式正确；餐次配比合计 1.0；热量/蛋白出口双钳制；忌口过滤有零分母保护。
- **DeepSeek 红线**：超时/流关闭/断连齐全；Key 只进 Authorization header，不进日志与异常消息；断网回落链路完整，开关默认关闭。
- **图表**：TrendChart/CategoryPieChart/HeatmapGrid/完成环均无除零、无索引越界、角度累加正确。
- **DI / 构建**：DataStore 用单一实例委托；Qualifier 无混用；签名配置对缺文件安全；proguard 对 domain.model/kotlinx-serialization/Hilt/Room 保留规则齐全；`data_extraction_rules.xml`/`backup_rules.xml` 存在且被正确引用。
- **内置数据**：51 个动作、127 条食物无重复名/重复 ID，营养值与公开成分表口径相符，忌口标签合法。

---

## 七、修复优先级建议

1. **先修三个 P1**（改动都很小）：P1-1 换取数入口、P1-2 补一行 `equipment`、P1-3 merge 错误清空（与 TodayViewModel 对齐，顺带修 P2-3）。
2. **再修跨天类 P2**：P2-4（Discipline 接 `TodayClock.epochDay`，工程里已有现成模式可抄）、P2-6、P2-5（`observeActiveDaysSince` 加 `completed_sets > 0` 或 mask=0 时删行）。
3. **补异常防线**：P2-8、P2-9、P2-12（透传异常 message 或映射专门文案）、P3-26/29。
4. **P2-1**（餐次槽位预检 + 专属文案）、**P2-10**（`weight(1f)`）、**P2-2**（upsert 合并条件扩展）随后处理。
5. **上架前**处理 P3-34（删 `USE_EXACT_ALARM`，只影响 Play 审核，不影响功能）。
6. 其余 P3 可按迭代节奏消化，其中 P3-1（Converters 枚举兜底）建议比照 FoodEntity 已有的"安全兜底 mapper"模式一次修完。

---

## 八、检验局限性声明

- 本报告为**纯静态审查**，未运行编译、单元测试与真机验证（本机无 Android SDK；工程设计的构建/测试通道为 GitHub Actions）。若需运行验证，可在 CI 执行 `./gradlew testDebugUnitTest`（工程含 90+ 单测）后比对。
- 标注"疑似"的条目行为已核实，但触发条件依赖设备/时序/产品口径裁量，是否按缺陷处理由维护者决定。
- P2-13 的崩溃判定依赖 kotlinx-serialization 1.7.3 内部实现对深嵌套输入的防护行为，未能离线核实库源码，已在条目中如实标注。
- 审查以"错误运行时行为"为准入门槛，不含代码风格、命名、性能调优类意见。

*（报告生成：ZCode 静态检验 · 6 路并行深审 + 人工逐条复核 P1/P2 证据）*
