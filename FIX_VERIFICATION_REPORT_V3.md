# IronHabit App 修复验证报告（V3）

| 项目 | 内容 |
|---|---|
| 验证对象 | `D:\fitness-app-v204 1`（对 2026-09-27 修复波次逐条验收） |
| 验证方式 | **只读**静态复核（未修改任何代码）+ **实际运行完整单测** |
| 验证日期 | 2026-09-30 |
| 前序报告 | [BUG_INSPECTION_REPORT.md](BUG_INSPECTION_REPORT.md)（V1，50 条）→ [BUG_INSPECTION_REPORT_V2.md](BUG_INSPECTION_REPORT_V2.md)（V2，60 条） |
| 验证方法 | 4 路并行逐条验收（数据层/Domain-AI/UI/横切回归）+ 关键项人工回源码复核 + 完整单测实跑 |

---

## 一、总体结论

**修复质量高：前两轮全部 3 个 P1 已修复，15 个 P2 修复了 14 个，且修复普遍带注释溯源和同步单测。当前代码完整单测实跑通过（98 个测试类 / 864 个用例 / 0 失败）。**

但修复波次未覆盖全部清单，且自身引入了 1 个新 P2：

| 60 条原发现的验收结果 | 条数 | 明细 |
|---|---|---|
| ✅ 已修复 | **23** | P1 全部 3 条；P2 14/15 条；P3 6 条 |
| 🟡 部分修复 | **5** | V2-P3-3、P3-17、V2-P3-5、V2-P3-7、P3-22（各留一个口子） |
| ⚪ 设计保留 | **1** | P3-16（DeepSeek 30s 超时/2000 token，维持原值） |
| ❌ 未修复 | **31** | **P2-13（深嵌套 JSON 崩溃）+ 30 条 P3** |
| 🔴 修复波次新引入 | **1 P2 + 9 P3** | 最重要：换餐次槽位不迁移 meal_items 明细（见第二节） |

### 运行时验证（本轮新增）

```
gradle(8.9) testDebugUnitTest → BUILD SUCCESSFUL
98 个测试类 · 864 个用例 · 0 失败 0 错误
```
（此前两轮报告均未运行编译/测试；本轮起补充运行时证据。注：gradlew.bat 要下载 Gradle 8.11.1 会超时，本机用 `C:\Users\science\android-tools\gradle-8.9` 实跑。）

---

## 二、⚠️ 修复波次引入的新问题（优先处理）

### 新-P2【P2】编辑一餐换到"已删除"槽位时，已记的饮食明细不随内容迁移，从当日摄入中消失
- **位置**：`data/local/dao/MealDao.kt:203-219`（两个独立审查代理分别发现，结论一致，并经人工复核确认）
- **机制**：`upsertUser` 的换槽分支把编辑内容搬进软删占位行并复活它、`softDelete(byId.id)` 软删原行——但**没有任何 `meal_items` 重挂**。而 `MealItemDao.observeByDate`（:28-36）与 `MealIntakeCalculator` 都以 `JOIN meals … is_active = 1` 按餐归组取数。
- **后果**：用户在一餐记过明细（真实吃了什么），再把这餐改到一颗 DELETED 餐次 chip（`MealEditSheet.kt:148` 允许并只提示"恢复并替换"）后，明细行挂在被软删的旧 id 上，从当日摄入明细和 `DietTally`（itemCount/itemKcal/filledMealCount）中同时消失，摄入退化为整餐粗记值。缓解因素：旧行可通过"已删除 N 条 · 恢复"找回连带明细（数据未销毁），但弹层提示只字未提。
- **修复方向**：换槽分支内补一句 `UPDATE meal_items SET meal_id = occupant.id WHERE meal_id = byId.id`（复用现有 `MealItemDao.moveToMeal`），并同步提示文案。

### 新 P3（8 条，修复波次引入或暴露）
| # | 位置 | 问题 |
|---|---|---|
| 1 | `TodayScreen.kt:756,420-427` | 「已删除 N 条」里的恢复按钮未做未来日只读门禁（同分支的 MealBlock/重新生成都有 `enabled = !isFutureDay`），未来日可写库 |
| 2 | `PlanPreviewViewModel.kt:257/323/370` | 三处新增 `catch(Exception)` 吞 `CancellationException`（页面切走的取消被记成业务失败） |
| 3 | `FoodLibraryViewModel.kt:259` | `onSave` 新补的 `catch(Exception)` 同形吞取消 |
| 4 | `TodayViewModel.kt:761/772`、`TrainViewModel.kt:258/285/322/238`、`DisciplineViewModel.kt:159/175/192`、`AddEditHabitViewModel.kt:282`、`AddEditPlanViewModel.kt:161/284`、`SettingsViewModel.kt:226/254/393` | `catch(Throwable)`/`runCatching` 吞取消的残余清单（P3-22 只修了一半，位置见 NEW-FIXED.md 同类清单） |
| 5 | `TodayViewModel.kt:490-506` | 编辑餐弹层开着跨午夜保存，会把整餐搬到新的一天（弹层打开跨 00:00 并保存，窗口极窄；建议用弹层快照的 `dateEpochDay`） |
| 6 | `ExternalPlanDocumentParserTest` | 外部导入新增的重量钳制（`coerceWeightKg` 0..500）没有对应单测，修复成果无回归防护 |
| 7 | `AskCoachUseCaseTest.kt:111-118` | 把"空问题 → Failed(REMOTE_ERROR)"旧行为钉死为契约，后续按 P3-14 正确修复时测试会红——届时必须同步改测试 |
| 8 | `app/build.gradle.kts:141` | `security-crypto` 以字面量绕过版本目录（注释自述为临时回退），catalog 未收敛成技术债；另 `RestoreMealUseCase.kt:12-13` KDoc 对机制的描述与实际实现不符（结论碰巧成立） |

---

## 三、已修复清单（23 条，验收通过）

### P1（3/3）✅
| 编号 | 修复验证 |
|---|---|
| P1-1 教练上下文取全历史周 | `BuildCoachContextUseCase.kt:101` 已改 `observeEffectivePlanForWeek(currentWeekStart)` + 过滤已删动作；`BuildCoachContextUseCaseTest.kt:117-132` 钉死周口径（strict mock 下谁改回 `observeAll()` 当场炸） |
| P1-2 远端建议丢 equipment | `RemoteLlmAdvisor.kt:148` 已补 `equipment = candidate.equipment`；`RemoteLlmAdvisorTest.kt:310-319` 断言透传 |
| P1-3 训练页错误态粘滞 | `TrainViewModel.kt:328-337` merge 改 `errorRes = data.errorRes`，随数据恢复自动清除 |

### P2（14/15）✅
| 编号 | 修复验证 |
|---|---|
| P2-1 换餐次撞唯一约束 | `MealDao.upsertUser:197-247` 双分支处理 + 新增 `GetMealSlotStatesUseCase`（FREE/TAKEN/DELETED 三态）+ TAKEN chip 灰化 + `error_duplicate_meal` 专属文案（`TodayViewModel.kt:517-524`）；`MealSlotStatesTest` 配套 |
| P2-2 一键打卡抹已录数据 | `QuickCheckInUseCase.kt:79-89`（`keepingLoggedDetails`：旧行非 quick 时保留 reps/weight/duration/notes/rpe）+ `BackfillCheckInUseCase.kt:41-43`；逐字段断言的单测配套 |
| P2-3 自律页错误粘滞 | `DisciplineViewModel.kt:217-223` 同 P1-3 口径 |
| P2-4 自律页跨午夜写昨天 | `DisciplineViewModel.kt:54,116-118` 已接 `TodayClock.epochDay`，换天整条重订阅 |
| P2-5 mask=0 残行计活跃日 | 采用查询过滤口径：`observeActiveDaysSince` 加 `completed_sets > 0`，StatsDao 六条聚合 + TrainingDayResolver 全部闭环；mask=0 保留行为改为 KDoc 声明的刻意取舍（保 RPE/备注） |
| P2-6 历史/档案"今天"冻结 | `HistoryViewModel.kt:104-112`、`ProfileViewModel.kt:74-77` 均改 `combine(retryTrigger, todayClock.epochDay) → flatMapLatest` |
| P2-7 / V2-P2-2 四页错误死锁 | Settings/BodyMetrics/History/ExerciseDetail 四页全部加 retryTrigger + 页内重试按钮（含 `HistoryViewModelRetryTest` 等配套） |
| P2-8 计划预览无兜底 | `PlanPreviewViewModel.kt` load(:195-259)/commit(:301-330)/onApplyProfile(:357-375) 三处补 catch 并复位 busy |
| P2-9 食物库保存崩溃 | `FoodLibraryViewModel.kt:259-264` 补 catch（注：catch(Exception) 吞取消 → 新-P3-3） |
| P2-10 训练页底部裁切 | `TrainScreen.kt:151-184` 三分段包进 `Box(Modifier.weight(1f))` |
| P2-11 双弹层叠加 | `TodayScreen.kt:342-347` 及全部第二层入口先 `sheetTarget = null` |
| P2-12 导入错误被降级 | `BackupViewModel.kt:52-66` 改为按 `BackupImportFailure.Kind` 分档展示"文件过大/无法读取"；`ImportDataUseCase.kt:41-61` 返回带 Kind 的 failure |
| V2-P2-1 接收器无兜底 | `ReminderReceiver.kt:85-91`、`BootReceiver.kt:53-59` 补 catch；`AppModule.kt:49-58` 给 ApplicationScope 挂上新增的 `ApplicationCoroutineExceptionHandler`（并有源码扫描测试钉住装配） |

### P3（6 条）✅
| 编号 | 修复验证 |
|---|---|
| P3-2 moveTo 无事务 | `MealItemRepositoryImpl.kt:58-60` 委托新增的 DAO `@Transaction moveToMealAtEnd`（只碰两列，不再整行读改写） |
| V2-P3-1 坚果漏标 PEANUT | `foods.json` 核桃仁/杏仁/腰果三条已补（`FoodSeederTagBackfillTest` 配套） |
| P3-23 习惯勾选双击竞态 | `TodayViewModel.kt:302-313` 改显式目标态 `done = !item.isCompletedToday`，幂等目标写 |
| V2-P3-6 AddEditPlan 周固化 | `newRowWeek` 由 val 改函数，保存时求值（:118-122,260） |
| P3-33 时间格式 Locale | `SettingsScreen.kt:869`、`SettingsViewModel.kt:417` 均 `String.format(Locale.ROOT, …)` |
| P3-34 双精确闹钟权限 | Manifest 已删 `USE_EXACT_ALARM`（:20 仅留 `SCHEDULE_EXACT_ALARM`，附降级策略注释） |

---

## 四、部分修复（5 条，各留一个口子）

| 编号 | 已修部分 | 未修部分 |
|---|---|---|
| V2-P3-3 init 裸 collect | FoodLibrary 的 Room 流已加 `.catch`（:102-121） | `onDeactivate/onActivate`（:276-284）仍裸 launch 写库 |
| P3-17 重量无上限 | 外部导入路已钳 `coerceWeightKg`（`ExternalPlanDocumentParser.kt:562-564`） | 远端 DeepSeek 路（`RemoteLlmAdvisor.kt:84`）仍无上限 |
| V2-P3-5 Keystore 主线程 | 崩溃面已修（懒加载 + runCatching + openFailed；MainActivity 写标记也有兜底） | `SettingsViewModel:149`、`AiCoachViewModel:194/314` 仍在主线程同步触发首次 `EncryptedSharedPreferences.create`，卡顿/ANR 风险未移 IO |
| V2-P3-7 训练页周分裂 | UI 层全修（`TrainViewModel.kt:77-78,90-94` 动态周 + 重订阅） | 仓库层 `PlanRepositoryImpl.observePlansForDay:48-49` 仍焊死 `currentWeekStart()`，靠 KDoc"消费者须重订阅"约束 |
| P3-22 吞 CancellationException | Today/AiCoach/ExternalImport 等 10+ 处已修（多处先 rethrow） | 残余清单见 新-P3-4（约 13 处） |

---

## 五、未修复清单（31 条）

**P2（1 条）——最值得下一个修：**
- **P2-13 深嵌套 JSON 栈溢出**：`ExternalPlanDocumentParser.decode`（:674-689）仍无深度预检，`gradle/libs.versions.toml` 仍是 kotlinx-serialization 1.7.3（深度上限 1.9.x 才有）。外部粘贴百层嵌套 JSON ≤512KB 可崩 App。修法：解析前数括号深度（≤64），一行循环。

**P3（30 条）**，按域分组：
- AI/远端（8）：P3-9 幂等空更新仍累加 timesUsed；P3-11 rows==null 话术与投影器不一致；P3-12 克数路径清份量标签；P3-15 单例成对状态非原子；P3-18 渐进超负荷无上限；P3-19 四反引号围栏拒收；P3-20 空模板除零防御；P3-21 重复 dayOfWeek 不去重。
- 数据/仓库（6）：P3-1 Converters 枚举未知值 NPE 风险；P3-4 copyIntoWeek 复活软删槽位；P3-5 闹钟自续期不查 isActive；P3-6 导出三 Flow 非原子；P3-8 resetStorage 复位在锁外；P3-30 import runCatching 吞取消。
- UI（8）：V2-P3-4 设置三处读-改-写丢更新；一次性事件仍用 StateFlow（无 Channel）；P3-24 同文案 snackbar 丢失；P3-25 每次切动作库重花一次 completion；P3-26 ExternalImport parse 无兜底；P3-27 copied 残留；P3-28 导出失败重试走错动作；P3-29 预览页空快照卡页。
- 用例层（4）：P3-10 PlanPreview 部分采纳快照滞留；P3-13 UpsertMeal 空条目未强制；P3-14 空问题归类误导（且被测试钉死，见新-P3-7）；Domain 其余未动条目。
- 测试（3）：B-3 七个测试类 Clock.System 快照 flake 窗口；新-P3-6/7（见第二节）。
- 备份（1）：V2-P3-2 v1/v2 导入重置主题/单位/提醒（`BackupRepositoryImpl.kt:206-212` 未动；如认可"整体替换"为兼容口径，建议在 KDoc 正式声明结案）。

---

## 六、修复波次整体评价

- **改动面**：主源码改 48 个文件、新增 3 个（`ApplicationCoroutineExceptionHandler`、`GetMealSlotStatesUseCase`、`RestoreMealUseCase`）；测试改 16 个、新增 2 个；资源 2 个；构建/清单/CI/文档 4 个；无删除、无悬空引用。
- **亮点**：① 修复普遍在代码注释里溯源编号（P1-1/P2-4/P2-10 等），可维护性好；② 关键修复全部带同步单测，且抽查 7 个新测试均为"真测"（逐字段断言、反向用例、防假通过处理）；③ 新功能链路自洽（餐次三态、恢复餐次、异常 handler 装配均有测试钉住）；④ `versionCode 24 / 2.0.13`、compileSdk 35、release 签名改读本机 `keystore.properties`（已确认 `.gitignore` 覆盖，无密钥泄漏风险）。
- **主要遗漏**：修复集中在 P1/P2 与 UI 层，AI/远端域的 12 条行为类 P3 与数据层备份条目基本未动；P2-13 这条唯一剩余的 P2 恰在崩溃路径上，建议与 新-P2（meal_items 迁移）一起作为下一批修复的头两项。

---

## 七、下一步建议（按优先级）

1. **新-P2**：`MealDao.upsertUser` 换槽分支补 meal_items 迁移（一句 UPDATE + 提示文案 + 单测）。
2. **P2-13**：外部导入解析前加嵌套深度预检。
3. **取消语义收尾**：新-P3-2/3/4 三组合并处理（全部 `catch`/`runCatching` 处先 rethrow `CancellationException`），一次清账。
4. **V2-P3-4** 设置读-改-写加 Mutex（或复用 persist 单 flight）。
5. 其余 30 条 P3 按第五节清单分批消化；`AskCoachUseCaseTest` 在改 P3-14 时记得同步改测试。

## 八、验证局限

- 修复验收为静态复核 + 单元测试实跑；未跑 `connectedDebugAndroidTest`（3 个 androidTest，需设备）与真机回归。
- 本机 Gradle 8.9 与 wrapper 声明的 8.11.1 存在版本差（wrapper 下载超时未能换用），CI 上仍按 wrapper 版本构建，行为一致性以 CI 为准。
- "未修复"判定以两份前序报告的口径为准；个别条目（如 P3-16 超时参数）已注明属设计裁量。

*（V3 报告生成：ZCode 修复验收 · 4 路逐条复核 + 新问题人工验证 + 完整单测实跑）*
