> ⚠️ **历史审查/修复记录（2026-09）。其中「联网一期 / DeepSeek / API Key / INTERNET 权限」相关条目已于 v2.0.14 整条撤销** —— 见 docs/ARCHITECTURE.md §2.13 的撤销记录。文中登记为「已修复」的 P0-2 / V2-P3-5（Keystore 兜底、加密存储主线程）等条目随 AiCredentialsStore 一起删除，不要再按本文去代码里找它们。
>

# IronHabit Bug 修复验收报告（V4）

| 项目 | 内容 |
|---|---|
| 修复依据 | [BUGFIX_PLAN.md](BUGFIX_PLAN.md)（经确认的批次 A / B / C1 / C4） |
| 修复日期 | 2026-09-30 |
| 约束遵守 | **AI 教练区域零改动**（`domain/ai/**`、`ui/screens/ai/**`、`AiCredentialsStore`、`DeepSeekClient` 及其测试均未触碰） |
| 回滚基线 | 本机无 git，改为目录快照 `D:\fitness-app-v204 1_baseline_20260930`（修复前完整副本） |
| 运行时验证 | `gradle(8.9) testDebugUnitTest`：**100 个测试类 / 868 用例 / 0 失败**（基线为 98 类 / 864 用例）；`compileDebugAndroidTestKotlin` 通过 |

---

## 一、TL;DR

按已确认的方案完成了**批次 A 全部 8 项、批次 B 全部 9 项、C1 / C4**（C2、C3 为可选项且未获勾选，未做，见第四节）。主源码改动 20 个文件、新增 1 个，测试新增 3 个文件、修改 8 个。所有改动均不触碰 Room schema（无新增迁移）、不触碰 AI 包、strings.xml 只增不改。AI 区域删除功能落地后，剩余 P3 大多自然消失。

---

## 二、批次 A（数据完整性 + 崩溃防线）—— 8/8 完成

| # | 条目 | 实际改动 | 测试 |
|---|---|---|---|
| A1 | **换餐次槽位不迁移 meal_items（新-P2）** | `MealItemDao` 新增 `migrateAllItems`（`@Transaction`：先平移目标餐 sort_order 腾位、再整批改挂）；`MealRepositoryImpl.upsert` 包进 `withTransaction`，用 `newId != sourceId` 精确判别换槽分支并触发迁移；`meal_slot_deleted_hint` 文案补"明细会一并跟过去" | 新增 androidTest `MealSlotSwapMigrationTest`（换槽迁移 + 普通编辑不迁移两用例；已编译验证，运行需真机/CI） |
| A2 | **CE 吞噬清账（P3-22 残余 + 新-P3-2/3/4）** | 16 处补 `catch (cancellation: CancellationException) { throw cancellation }`（Today×5、Train×4、Discipline×3、AddEditHabit×2、AddEditPlan×2、Settings×5、PlanPreview×3、FoodLibrary×3、BodyMetrics×2、AddEditExercise×1、BackupViewModel×2、BackupRepositoryImpl×1 —— 契约测试逼出的全部站点） | 新增**源码扫描契约测试** `CancellationExceptionContractTest`：扫描 `ui/screens`（除 ai 子包）与 `data/repository`，任何通用 catch / onFailure 写法必须先接取消，永久防回归。它已经逼出 3 处漏网站点并当场修掉，证明有效 |
| A3 | **设置页读-改-写丢更新（V2-P3-4）** | `SettingsViewModel.persist` 加 `Mutex`，三处 toggle（器械/伤病/忌口）天然串行化 | 新增 `SettingsViewModelToggleRaceTest`：虚拟时间拉长写盘、断言两次并发翻转都落库 + 同项翻两次幂等 |
| A4 | **import() 吞取消（P3-30）** | `BackupRepositoryImpl.import` 从 `runCatching` 改为显式 try/catch，取消重抛；主体抽成 `importPayload` | 现有 `BackupImportMessageTest`/`BackupRestoreRulesTest` 回归通过 |
| A5 | **闹钟给软删习惯续命（P3-5）** | `ReminderReceiver` 判据 `habit == null` → `habit?.isActive != true`，附根因注释 | 代码评审（竞态窗口无法单测）；`ReminderTriggerTimeTest` 回归通过 |
| A6 | **未来日可恢复已删餐（新-P3-1）** | `MealDeletedRow` 增加 `enabled` 参数，调用侧接 `!isFutureDay`（与同分支 MealBlock/重新生成同口径） | UI 评审；`TodayViewModelMealEditTest` 回归通过 |
| A7 | **编辑餐跨午夜搬到新的一天（新-P3-5）** | `onSaveMealEdit` 的 `epochDay` 改用被编辑餐自身的 `dateEpochDay` 快照（语义：编辑是对那一行的修改），KDoc 同步改写 | `TodayViewModelMealEditTest`：原"所选日"用例改写为"餐自身日期"用例 + **新增跨午夜用例**（游标跨天保存仍写原日） |
| A8 | **食物库启停裸 launch（V2-P3-3 残余）** | `onDeactivate/onActivate` 补 catch + CE 重抛；`FoodLibraryUiState` 新增 `errorRes`，`FoodLibrarySheet` 列表态显示 3 秒自动清除的错误行；新增 `onConsumeError` | 纳入 A2 契约测试覆盖 |

## 三、批次 B（非 AI 区域 P3）—— 9/9 完成

| # | 条目 | 实际改动 |
|---|---|---|
| B1 | 枚举转换器未知值 NPE（P3-1） | `Converters.kt` 四个枚举转换器由"未知值回落 null"改为"回落默认成员"（ExerciseCategory→BODYWEIGHT、ExerciseSource→CUSTOM、HabitFrequency→DAILY、BodyMetricType→WEIGHT），每个附选择理由；null 直通保留给真正可空的使用点 |
| B2 | 复制到下周复活软删槽位（P3-4） | `WeekPlanDao` 新增 `hardDeleteInactiveById`（全工程唯一允许 DELETE week_plans 处，带 `is_active = 0` 闸 + 事务内补插约束注释）；`copyWeekInto` 换槽时先物理清位再插入；生效行维持原合并口径 |
| B3 | 导出设置非原子快照（P3-6） | 三个 DataStore Flow 改 `combine(...).first()` 一次取齐 |
| B4 | 克数路径清份量标签（P3-12） | `ChangeMealItemPortionUseCase`：serving 为 null 时回落既有 `servingUnit/servingCount` 快照 |
| B5 | 全空条目落空餐（P3-13） | `UpsertMealUseCase` 清洗后为空即返回 `-1L` 哨兵不落库；`TodayViewModel.onSaveMealEdit` 识别后落 `error_meal_items_empty` 且保持弹层打开 |
| B6 | 预览陈旧快照（P3-10） | `PlanPreviewHolder` 新增 `adoptedDays` 会话级记录（set/clear 一并重置）+ `markDaysAdopted/peekAdoptedDays`；`PlanPreviewViewModel.commit` 成功后记录、`load` 重渲染时把已采纳天标为「已采纳」而非草案 |
| B7 | 预览空快照卡页（P3-29） | `load()` peek==null 时置 `finished = true` 直接送回上一页（进程重建后快照必然丢失，与其晾在空页不如回原处重新生成） |
| B8 | 测试时钟 flake 窗口（B-3） | 7 个测试文件的 `Clock.System` 全部换为固定时钟（2026-09-20T04:00Z）：CheckInRpeUpsert、StreakDirtyData、TodayViewModelMealEdit、TodayViewModelMealItem、AddEditPlanViewModelWeekScope、AddEditHabitViewModelTest、BodyMetrics/AddEditExercise/AddEditPlan 三个 InputLimits 测试（后三者经核实无日期断言，安全） |
| B9 | RestoreMealUseCase KDoc 失实（新-P3-9） | 注释改为真实机制（upsertGenerated 原地复用同一行） |

## 四、批次 C

| # | 结论 |
|---|---|
| C1 | **已修**：`applySettings` 加版本闸（`schemaVersion >= PROFILE_SNAPSHOT_SCHEMA_VERSION` 才写 theme/unit/reminder），v1/v2 老备份不再洗掉本机主题/单位/提醒设置；KDoc 更新 |
| C4 | **已修**：`DietPlanGenerator.buildDraft` 空模板库守卫（返回空草案，与"全空保留空条目"同口径） |
| C2 / C3 | **未做**（方案中标注为可选项且需单独勾选）：动作库建议每次切换重拉（P3-25）、同文案 Snackbar 被去重吞掉（P3-24）。需要的话随时可补，改动都很小 |

---

## 五、改动清单（本会话）

**主源码（20 个文件）**：
`MealItemDao`、`MealRepositoryImpl`、`WeekPlanDao`、`PlanRepositoryImpl`、`Converters`、`BackupRepositoryImpl`、`ReminderReceiver`、`ChangeMealItemPortionUseCase`、`UpsertMealUseCase`、`PlanPreviewHolder`、`DietPlanGenerator`、`RestoreMealUseCase`（仅注释）、`TodayViewModel`、`TodayScreen`、`TrainViewModel`、`DisciplineViewModel`、`AddEditHabitViewModel`、`AddEditPlanViewModel`、`AddEditExerciseViewModel`、`BodyMetricsViewModel`、`SettingsViewModel`、`PlanPreviewViewModel`、`FoodLibraryViewModel`、`FoodLibraryUiState`、`FoodLibrarySheet`、`BackupViewModel`、`strings.xml`（1 处文案强化）

**新增测试（3 个文件）**：`MealSlotSwapMigrationTest`（androidTest）、`CancellationExceptionContractTest`、`SettingsViewModelToggleRaceTest`
**修改测试（8 个文件）**：见 B8 清单 + `TodayViewModelMealEditTest`（语义改写 + 新增跨午夜用例）

## 六、验证

```
gradle(8.9) testDebugUnitTest   → BUILD SUCCESSFUL · 100 类 / 868 用例 / 0 失败
gradle(8.9) compileDebugAndroidTestKotlin → BUILD SUCCESSFUL
```
- 契约测试首跑即抓出 3 处 V3 清单之外的漏网站点（AddEditExerciseViewModel、BackupViewModel×2），已修 —— 扫描式测试从第一天起就在产生价值。
- androidTest 用例（A1 换槽迁移）本机无设备仅验证编译，需在 CI/真机跑 `connectedDebugAndroidTest` 实测。

## 七、遗留与后续

1. **AI 区域延后项**（本轮按约定未动）：P2-13 深嵌套 JSON 崩溃、P3-11/14/15/17远端/18/19/21、P3-8、P3-16、ai 包内 CE 残余。⚠️ 若重做的 AI 帮训功能仍解析外部粘贴文本，务必带上嵌套深度预检。
2. **C2 / C3**（可选，未勾选）。
3. **上架前**：Manifest 的 `USE_EXACT_ALARM` 已在 9-27 波次移除，无需再处理。
4. 建议下次会话把 `_baseline_20260930` 快照与本轮改动做成正式 git 历史（本机装 git 后 `git init` + 两次提交即可）。
5. V3 报告中的累计台账相应更新：60 条原发现中**已修复 48 条**（含本轮），延后 11 条全部位于 AI 删除范围内或为可选未勾选项，设计保留 1 条（P3-16 超时参数）。

*（V4 报告生成：ZCode · 按 BUGFIX_PLAN 批次施工 + 每批次全量单测验证）*
