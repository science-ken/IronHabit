# IronHabit（自律健身）交付前 QA 严过关报告

- 审查对象：`fitness-app`（Kotlin 2.0.21 / Compose BOM 2024.12.01 / Hilt 2.52 / Room 2.6.1，minSdk 24，compileSdk=targetSdk=34，纯离线）
- 审查规模：`app/src` 下 155 个文件 = 133 个 `.kt`（main 124 / test 7 / androidTest 2）+ 12 个 `res/xml/manifest`；另含 3 个 `.kts`、1 个 `libs.versions.toml`、3 个 CI workflow
- 审查方式：**纯静态**（逐文件人工通读 + 6 个脚本化交叉核对）。本环境**无 JDK / Android SDK / Gradle 缓存 / 模拟器**，无法执行 `compileDebugKotlin`、`testDebugUnitTest`、`assembleDebug`、`connectedAndroidTest`
- 审查人：QA 工程师（Edward）｜审查时间：2026-09-14｜轮次：Round 1（发现问题）→ Round 2（回归复核）

> **后记（2026-09-14，首次真实编译之后）**：本报告开头「审查对象」一行所述 `compileSdk=targetSdk=34` 为**本报告审查当时**的工程状态，已如实保留。此后工程**首次真实编译**（本机 JDK 17 + Android SDK + Gradle 8.11.1）在 `:app:checkDebugAarMetadata` 阶段报出：`androidx.core:core:1.15.0` / `core-ktx:1.15.0` 的 AAR 元数据要求 `compileSdk >= 35`。故 **compileSdk 已升为 35**；**targetSdk 保持 34、minSdk 保持 24 不变**（compileSdk 只影响编译期可用 API 面，不改变运行时行为；targetSdk 一旦升级会引入 Android 15 的运行时行为变更，故刻意不动）。此变更**发生在本次静态审计之后**，不影响本报告其余结论的有效性。

---

## §0 结论摘要

### 结论：**PASS（可交付）** —— 经 Round 2 回归确认

Round 1 审查发现 **7 项缺陷（4 HIGH + 1 MEDIUM + 2 LOW）**，已全部由 software-engineer 修复（任务 #11）；QA 于 Round 2 逐条复核当前工作树，**7/7 全部关闭、未发现回归**。首轮判定"无法编译"的 3 类 Kotlin 语言规则级问题（C1/C2/C3）与 1 处必崩运行时缺陷（C4）均已消除。

| 严重级别 | 数量 | Round 1 结论 | Round 2 复核 |
|---|---|---|---|
| HIGH（阻断编译） | 3 类 / 13 处 | C1 缺 import（1）+ C2 Flow 类型链（1）+ C3 委托属性智能转换（11） | **已修复**（§10.1 逐条证据） |
| HIGH（必崩运行时） | 1 处 | C4 `%1$d` 收到 String → `IllegalFormatConversionException` | **已修复**（占位符改 `%1$s`） |
| MEDIUM | 1 处 | M1 图标语义与行为不符 + 重复入口 | **已修复**（新增真实跳转回调，链路闭合） |
| LOW | 2 处 | L1 KDoc 与实现不符；L2 死代码 | **已修复** |
| INFO | 3 项 | 未引用字符串 / 测试调度器 / DAO 单例 | 非缺陷，保留备查 |
| 遗留（**非阻断**） | 2 项 | — | N1 `TodayViewModel` 残留未用 import；N2 同文件 KDoc 文案陈旧（均 warning 级，不影响编译与运行） |
| **合计** | **7 项缺陷** | — | **7/7 关闭，PASS** |

> 判定依据回顾：C1/C2/C3 违反的是 Kotlin 语言规范（非风格问题）。其中 C3 依据 Kotlin 官方语言参考原文 *"val local variables — Always, **except local delegated properties**"*（`kotlinlang.org/docs/typecasts.html`）以及 Android 官方文档 *"Kotlin cannot smart-cast a by-delegated property directly"*。Round 1 之所以判 FAIL，是因为这些是**编译期硬失败**；Round 2 已确认 11 处全部改为"先取本地 val 再判空"的合法写法。

### 无法验证项（环境限制，Round 1/Round 2 均未能覆盖，属交付风险）

1. **编译**：无 JDK/Kotlin 编译器，未能执行 `./gradlew assembleDebug` / `compileDebugKotlin`；本报告的编译结论为**静态推演**（规则级、非风格级），置信度高但未经编译器确证。
2. **单元测试执行**：无 JVM 构建环境，18 个 JVM 用例未能实际运行；本报告对每个用例做了**逐条人工推演**（见 §3）。
3. **Instrumentation 测试执行**：无模拟器/真机，8 个 androidTest 用例未运行。
4. **App 安装与启动**：无真机，未验证冷启动、启动图、首启播种、7 个 DAO 建库。
5. **通知实际弹出**：未验证 `AlarmManager` 精确闹钟到点触发、`ReminderReceiver` 自续期、通知渠道与震动。
6. **UI 交互**：未验证点击/长按/滑动、弹层、Tab 切换、返回栈、深浅色切换与旋转。
7. **Hilt/KSP/Room 注解处理产物**：未生成，未验证 `@HiltAndroidApp` 组件、`AppDatabase_Impl`、`app/schemas/1.json`。
8. **R8/ProGuard 混淆产物**：未构建 release，未验证 minify/shrinkResources 后的可用性。

### 残余风险（真机/CI 兜底）

- 日期口径全部走 `kotlinx-datetime 0.6.1` 的 `toEpochDays()/atStartOfDayIn/toLocalDateTime`，跨时区、夏令时、设备改时间后的 streak/热力图首尾两天正确性，只能靠真机验证。
- `%1$d`/`%1$s` 之外的字符串占位符与参数类型配对，本次仅覆盖了"带参数"的 5 处调用（见 §3.2），其余靠编译器/lint 兜底。
- 通知/精确闹钟在各厂商 ROM 上的行为（尤其国产 ROM 后台限制）不可静态判定。

---

## §1 Hilt 依赖图完整性

**检查项：4 个 DI 文件 + 1 个 `@EntryPoint` + 全部 `@Inject` 构造器 + 4 个 `@AndroidEntryPoint`/`@HiltAndroidApp` 入口。结论：依赖图完整，**未发现缺失绑定、未发现循环依赖、未发现无法满足的注入点**。**

### 1.1 绑定源清点（共 27 个绑定）

| 模块 | 提供内容 | 数量 |
|---|---|---|
| `di/Qualifiers.kt` | `@IoDispatcher` `@DefaultDispatcher` `@ApplicationScope` 限定符定义 | 3 |
| `di/AppModule.kt` | `IoDispatcher`=Dispatchers.IO、`DefaultDispatcher`=Dispatchers.Default、`@Singleton @ApplicationScope CoroutineScope(SupervisorJob+IO)`、`Clock`=Clock.System、`TimeZone`=currentSystemDefault | 5 |
| `di/DatabaseModule.kt` | `AppDatabase`（`@Singleton`，`fallbackToDestructiveMigrationOnDowngrade()`）+ 7 个 DAO | 8 |
| `di/NotificationModule.kt` | `@Binds ReminderSchedulerImpl→ReminderScheduler`、`NotificationManagerCompat`、`AlarmManager` | 3 |
| `di/RepositoryModule.kt` | `@Binds` 8 个仓库实现 → 接口（Exercise/Plan/CheckIn/Habit/Stats/BodyMetric/Settings/Backup） | 8 |

### 1.2 注入点逐个核对（全部满足）

- `@HiltAndroidApp IronHabitApp`（`IronHabitApp.kt:23`）注入 `SeedExercisesUseCase`（`@Inject` 构造器）+ `@ApplicationScope CoroutineScope`（已提供）→ OK
- `@AndroidEntryPoint MainActivity`（`MainActivity.kt:18`）→ OK
- `@AndroidEntryPoint ReminderReceiver`（`ReminderReceiver.kt:22`）注入 `NotificationHelper`（`@Singleton @Inject` 构造器）、`ReminderScheduler`（`@Binds`）、`@ApplicationScope CoroutineScope` → OK
- `@AndroidEntryPoint BootReceiver`（`BootReceiver.kt:19`）注入 `ReminderScheduler`、`@ApplicationScope CoroutineScope` → OK
- `ReminderSchedulerImpl`（`ReminderSchedulerImpl.kt:29`）注入 `@ApplicationContext Context`、`AlarmManager`、`SettingsRepository`、`HabitRepository`、`Clock`、`TimeZone` → 全部有源 → OK
- `NotificationHelper`（`NotificationHelper.kt:27`）注入 `Context`、`NotificationManagerCompat` → OK
- `@EntryPoint SettingsEntryPoint`（`ui/theme/Theme.kt:23-27`）暴露 `SettingsRepository`，由 `EntryPointAccessors.fromApplication(...)` 取用（`Theme.kt:44-49`）→ OK
- **11 个 `@HiltViewModel`**：Today / Train / Discipline / Profile / History / BodyMetrics / Settings / Backup / AddEditExercise / AddEditPlan / AddEditHabit —— 逐个核对构造器参数，全部由"仓库接口 + UseCase + `SavedStateHandle` + `Clock`/`TimeZone` + `ReminderScheduler`"组成，均有提供 → OK

### 1.3 唯一可讨论点（INFO，非缺陷）

`DatabaseModule` 的 7 个 `provideXxxDao` 未加 `@Singleton`。因 DAO 只被 `@Singleton` 仓库注入，实际不会重复创建代价，无功能影响。

**§1 结论：Hilt 依赖图 27 个绑定、11 个 ViewModel、4 个 Android 入口全部自洽，未发现问题。**

---

## §2 Room 正确性

**检查项：6 张实体表 + 7 个 DAO + 1 组 TypeConverter + 1 个 `@Database` 声明。结论：SQL 列名/表名/实体字段三方完全一致，唯一约束与 upsert 幂等语义正确。**

### 2.1 表 ⇄ 实体 ⇄ SQL 一致性（逐表核对 `@Query` 用到的每个列名）

| 表（tableName） | 实体文件 | SQL 中出现的列 | 一致 |
|---|---|---|---|
| `exercises` | `ExerciseEntity.kt` | id, name, category, muscle_group, is_built_in, is_active, default_sets, default_reps, default_duration_sec, times_used, sort_order, created_at | ✅ |
| `week_plans` | `WeekPlanEntity.kt` | id, exercise_id, day_of_week, target_sets, target_reps, target_weight_kg, target_duration_min, sort_order, is_active, created_at | ✅ |
| `check_ins` | `CheckInEntity.kt` | id, exercise_id, plan_id, date_epoch_day, date_start_millis, completed_sets, completed_reps, weight_kg, duration_minutes, notes, is_quick, logged_at_millis, created_at | ✅ |
| `habits` | `HabitEntity.kt` | id, name, emoji, color_hex, frequency, weekly_days_mask, reminder_enabled, reminder_hour, reminder_minute, is_active, sort_order, created_at | ✅ |
| `habit_logs` | `HabitLogEntity.kt` | id, habit_id, date_epoch_day, date_start_millis, is_completed, note, logged_at_millis, created_at | ✅ |
| `body_metrics` | `BodyMetricEntity.kt` | id, type, value, unit, date_epoch_day, date_start_millis, note, created_at | ✅ |

- `@Database(entities=[6 张], version=1, exportSchema=true)`（`AppDatabase.kt:29-40`）与 6 个实体类一致；`abstract fun` 恰好 7 个（含 `statsDao`）。
- `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`（`app/build.gradle.kts:90-93`）已配置，`exportSchema=true` 不会报警且能生成 `app/schemas/1.json`。
- TypeConverter（`Converters.kt`）：`LocalDate↔Long(epochDay Int)`、`ExerciseCategory/HabitFrequency/BodyMetricType ↔ String`，DAO 里出现过 `WHERE category = :category`、`WHERE type = :type` 的枚举入参，**有对应 Converter，Room 能编译**；`valueOf` 用 `runCatching` 兜底防脏数据。
- 关键查询语义逐条核对：
  - `CheckInDao.observeActiveDaysSince` = `DISTINCT date_epoch_day WHERE >= ? ORDER BY DESC`（`CheckInDao.kt:32-36`）→ 与 `CheckInDaoTest:observeActiveDaysSinceReturnsDistinctDaysDescending` 断言 `[101,100]` 一致 ✅
  - `CheckInDao.getAll` `ORDER BY date_epoch_day`（`:55`）→ 与测试断言 `[100,101,102]` 一致 ✅
  - `HabitLogDao.observeActiveDays` 过滤 `is_completed = 1`（`HabitLogDao.kt:28`）→ 语义正确（未勾选不计入 streak）✅
  - `StatsDao.trendRows/heatmapRows` 分别映射 `DayCountRaw`/`TrendRaw`，两 DTO 字段名均为 `epochDay/count`，与 `AS epochDay, AS count` 别名一致 ✅
  - `WeekPlanDao.observeActiveByDay` 的 `INNER JOIN exercises` + 双 `is_active=1` 过滤（`WeekPlanDao.kt:16-24`）→ 停用动作的计划不展示，语义正确 ✅
  - 唯一约束：`check_ins(exercise_id,date_epoch_day)`、`habit_logs(habit_id,date_epoch_day)`、`week_plans(day_of_week,exercise_id)`、`body_metrics(type,date_epoch_day)`、`exercises(name)` —— 与各 upsert 幂等设计一致 ✅
  - FK：`check_ins.exercise_id` CASCADE、`week_plans.exercise_id` CASCADE、`habit_logs.habit_id` CASCADE；`check_ins.plan_id` **故意无 FK**（删计划不抹历史）—— 与 `CheckInEntity.kt:12-14` 注释及 `CheckInRepository.delete` 语义一致 ✅

### 2.2 唯一可讨论点（已在 Round 2 修复）

`HabitRepository.kt:32-33` 的 KDoc 曾写"观察某习惯**全部有记录**的日期"，但实现只返回 `is_completed=1`。**实现是对的**（streak 只应统计完成日），是注释描述不准（原 L1）——Round 2 已修正为"全部**已完成**的日期（`is_completed = 1`）"。

**§2 结论：6 表 / 7 DAO / 全部 SQL 列名与实体一致，唯一约束与幂等语义正确，未发现 Room 层缺陷。**

---

## §3 测试真实性审查（逐用例）

**检查项：6 个 JVM 测试类（18 个用例）+ 2 个 androidTest 类（8 个用例）= 26 个用例，逐条读断言并手工推演实现。结论：26/26 断言与实现、设计一致，未发现"假测试"（未发现空断言、未发现 `assertTrue(true)`、未发现无断言用例、未发现 `Thread.sleep`、未发现只测 getter 的凑数用例）。**

### 3.1 JVM 单元测试（18）

| # | 用例 | 断言 | 我的推演 | 有效 |
|---|---|---|---|---|
| 1 | `StreakCalculatorTest.emptyList_returnsZeroStreak` | `StreakInfo(0,0,null)` | 空表→分支①直接返回 (0,0,null) | ✅ |
| 2 | `consecutiveDays_incrementsCurrent` | `(3,3,20500)` | head=20500=today→current 1→2→3；best=3 | ✅ |
| 3 | `notCheckedInTodayButYesterday_keepsStreak` | `(2,2,20499)` | head=today-1 允许；20500-20499 链 2 天；best 2 | ✅ |
| 4 | `missedTwoDays_resetsCurrentButKeepsBest` | `(0,2,20497)` | head=20497 < today-1 → current=0；best=2 | ✅ |
| 5 | `backfillFilledGap_liftsCurrent` | `(2,2,20500)` 与 `(3,3,20500)` | 补卡前后 current 2→3，逻辑正确 | ✅ |
| 6 | `undoOnlyCheckIn_resetsToZero` | `(0,0,null)` 与 `(1,1,20499)` | 撤销唯一一次→空；仅剩昨天→1 | ✅ |
| 7 | `bestIsKeptForever_acrossGap` | `(2,4,20500)` | 近段 2 天、历史段 4 天；best 取 4 | ✅ |
| 8 | `DateUtilsTest.todayEpochDay_equalsLocalDateEpochDays` | 相等 | 固定 noon UTC Clock → 同一天 | ✅ |
| 9 | `startOfDayMillis_isLocalMidnight` | =`atStartOfDayIn` 毫秒，且 h/m/s=0 | 正确 | ✅ |
| 10 | `weekdayMon1_knownAnchors` | 0→4, 3→7, 4→1, base→6 | 手工核对：1970-01-01=周四(4)、1970-01-04=周日(7)、1970-01-05=周一(1)、2026-02-21=周六(6) 全部正确 | ✅ |
| 11 | `QuickCheckInUseCaseTest.writesCheckInWithPlanTargetsAndBumpsUsage` | exerciseId=5、planId=10、dateEpochDay=base、dateStartMillis、sets=4、reps=8、weight=20、isQuick=true；`bumpUsage(5L)` 恰 1 次 | 与 `QuickCheckInUseCase.kt:30-46` 逐字段一致 | ✅ |
| 12 | `BackfillCheckInUseCaseTest.backfillWritesHistoricalEntryMarkedNotQuick` | 字段 + `isQuick=false` + upsert 1 次 | 与 `BackfillCheckInUseCase.kt:26-41` 一致（且该用例不注入 `ExerciseRepository`，正确表达"补卡不 bumpUsage"） | ✅ |
| 13 | `ToggleHabitUseCaseTest.markDoneForwardsExactArguments` | `setLog(42,20500,true,null)` | 与 `ToggleHabitUseCase.kt:19-21` 精确透传一致 | ✅ |
| 14 | `markUndoneForwardsFalseFlag` | `setLog(7,20498,false,null)` | 一致 | ✅ |
| 15 | `CalculateStreakUseCaseTest.delegatesToPureCalculatorWithInjectedToday` | =`StreakCalculator.calculate(...)`；current/best=3、last=base | 一致 | ✅ |
| 16 | `todayNotLoggedButYesterdayLoggedDoesNotBreak` | `(2,2,base-1)` | 一致 | ✅ |
| 17 | `emptyInputYieldsZeroStreak` | `(0,0,null)` | 一致 | ✅ |
| 18 | `staleHistoryResetsCurrentButKeepsBest` | `(0,4,base-5)` | 5 天前起 4 连击 → current 0、best 4 | ✅ |

### 3.2 androidTest（8）

| # | 用例 | 断言 | 推演 | 有效 |
|---|---|---|---|---|
| 19 | `AppDatabaseTest.databaseOpensAndAllDaosAreAccessible` | 7 个 DAO 非空 | 与 `AppDatabase` 的 7 个抽象方法一致 | ✅ |
| 20 | `checkInUniqueConstraintMakesUpsertIdempotent` | 同动作同日两次 upsert → `countOn=1` | 唯一索引 + `REPLACE` | ✅ |
| 21 | `habitLogUniqueConstraintMakesUpsertIdempotent` | `getAll().size=1` | 同上 | ✅ |
| 22 | `CheckInDaoTest.upsertIsIdempotentOnSameExerciseAndDay` | `countOn=1` 且 `completedSets=5`（后写覆盖） | 一致 | ✅ |
| 23 | `observeBetweenReturnsOnlyRowsWithinClosedRange` | `[101,102]` | BETWEEN 闭区间 | ✅ |
| 24 | `observeActiveDaysSinceReturnsDistinctDaysDescending` | `[101,100]`（99 被排除、同日不同动作去重） | 一致 | ✅ |
| 25 | `deleteOnRemovesOnlyTheTargetedRow` | 仅删目标行，另一动作仍在 | 一致 | ✅ |
| 26 | `getAllReturnsRowsOrderedByDateAscending` | `[100,101,102]` | 一致 | ✅ |

### 3.3 对"字符串占位符 ⇄ 参数类型"的专项核对（Round 1 发现 C4，Round 2 已修复）

带参数的 `stringResource` 调用共 5 处（脚本全量扫描）：

| 调用点 | 资源 | 占位符 | 实参 | Round 1 结果 | Round 2 |
|---|---|---|---|---|---|
| `HabitRow.kt:63` | `label_streak_days` | `%1$d` | `item.streak.current`(Int) | ✅ | ✅ 未动 |
| `ProgressRing.kt:92` | `label_progress_ratio` | `%1$d %2$d` | 两个 Int | ✅ | ✅ 未动 |
| `ProgressRing.kt:97` | `label_streak_days` | `%1$d` | `streak.current`(Int) | ✅ | ✅ 未动 |
| `ProgressRing.kt:102` | `label_streak_best` | `%1$d` | `streak.best`(Int) | ✅ | ✅ 未动 |
| `SettingsScreen.kt:245` | `settings_version` | `%1$s` | String | ✅ | ✅ 未动 |
| `TodayViewModel.kt:217`（经 `TodayScreen.kt:62-63` 消费） | `msg_streak_up` | Round 1=`%1$d` / Round 2=`%1$s` | `current.toString()` → String | ❌ C4 | ✅ **已修**（占位符改 `%1$s`，类型匹配） |

### 3.4 覆盖率（估计）

- JVM 单测覆盖：`StreakCalculator`（纯函数，7 例，覆盖充分）、`DateUtils`（3 例，覆盖充分）、4 个 UseCase（各 1–2 例，仅 happy-path 透传）。
- **完全无单测**：8 个 Repository 实现、`StatsRepositoryImpl`（趋势/占比/完成率/密度分级）、`BackupRepositoryImpl`（导出/导入/事务回滚/`schemaVersion` 校验）、`ReminderSchedulerImpl`（排期/降级/最早习惯）、`SettingsDataStore`、全部 11 个 ViewModel、全部 Compose UI。
- 以 main 的 124 个 `.kt` 为分母，被测试类实质覆盖的约 8–10 个文件 → **估算行覆盖率 < 15%**（未跑 jacoco，仅估算）。

**§3 结论：26/26 现存用例真实、有效、断言与实现一致（无假测试）；覆盖面偏窄（Repository/ViewModel/Backup/Reminder 零覆盖）属**建议改进项**，非交付阻断（C4 已在 Round 2 关闭）。**

---

## §4 Manifest ⇄ 代码 ⇄ 资源 三角一致性

**检查项：`AndroidManifest.xml` 全部 4 类声明（application / activity / receiver×2 / provider）+ 所有 `@xml` `@mipmap` `@string` `@style` 引用。结论：三角形闭合，未发现悬空引用。**

| Manifest 声明 | 对应代码/资源 | 核对 |
|---|---|---|
| `android:name=".IronHabitApp"` | `IronHabitApp.kt`（`@HiltAndroidApp`） | ✅ 类存在 |
| `android:name=".MainActivity"`（exported=true，LAUNCHER） | `MainActivity.kt`（`@AndroidEntryPoint`） | ✅ |
| `.data.notification.ReminderReceiver`（exported=false） | `ReminderReceiver.kt`（`@AndroidEntryPoint : BroadcastReceiver`） | ✅ 类存在；由 `AlarmManager` 显式 PendingIntent 触发，**不需要 intent-filter**，exported=false 正确 |
| `.data.notification.BootReceiver`（exported=true，BOOT_COMPLETED/MY_PACKAGE_REPLACED/QUICKBOOT_POWERON） | `BootReceiver.kt`（`@AndroidEntryPoint : BroadcastReceiver`，`when(intent.action)` 三分支一致） | ✅ action 常量与 manifest 字面量逐一对齐 |
| `androidx.core.content.FileProvider`，authorities=`${applicationId}.fileprovider` | `ExportDataUseCase.kt:38-42` 用 `context.packageName + ".fileprovider"` | ✅ namespace/applicationId 均为 `com.ironhabit.app`，拼串一致 |
| `@xml/file_paths` | `file_paths.xml`（files/cache/external-files 三段 `path="exports/"`） | ✅ 与 `ExportDataUseCase` 写入 `filesDir/exports/` 匹配 |
| `@xml/data_extraction_rules` / `@xml/backup_rules` | 两文件均存在且语法正确 | ✅ |
| `android:icon/roundIcon` = `@mipmap/ic_launcher(_round)` | 5 套 dpi webp + anydpi-v26 自适应图标 | ✅ 10 个 webp 均非空（脚本核对） |
| `@style/Theme.IronHabit` / `@style/Theme.IronHabit.Starting` | `themes.xml` 两个 `<style>` 均定义（Starting 以 `Theme.SplashScreen` 为 parent，依赖已引入的 core-splashscreen 1.0.1） | ✅ |
| `@string/app_name` | `strings.xml:10` | ✅ |

- 全部代码内 `R.drawable/R.mipmap/R.color/R.xml` 引用：仅 `R.drawable.ic_notification`（`NotificationHelper.kt:49`），文件存在 ✅。
- 全部 163 个 `R.string.*` 引用均能在 `strings.xml`（176 个）中命中，**0 缺失** ✅。

**§4 结论：Manifest/代码/资源三角全部闭合；脚本对 `@style/Theme` 的"缺失"告警是正则误报（把 `.IronHabit` 后缀截掉了），实际两个主题均已定义。**

---

## §5 API 级别与权限守卫

**检查项：5 个声明权限 × 每处运行时使用是否都有 `Build.VERSION.SDK_INT` 守卫。结论：守卫齐备，未发现"低版本调用高版本 API"或"未检查权限即用"的缺陷。**

| 声明权限 | 最低生效 API | 代码使用点 | 守卫 | 判定 |
|---|---|---|---|---|
| `SCHEDULE_EXACT_ALARM` | 31 | `ReminderSchedulerImpl.kt:108-113` | `if (SDK_INT >= S) canScheduleExactAlarms() else true` —— 未授权自动降级 `setAndAllowWhileIdle`（`:99-104`） | ✅ |
| `USE_EXACT_ALARM` | 33 | 同上（`canScheduleExactAlarms()` 兼容两者） | 同上 | ✅ |
| `POST_NOTIFICATIONS` | 33 | `NotificationHelper.kt:62-72` | `if (SDK_INT >= TIRAMISU)` 再 `checkSelfPermission`，未授予静默返回 | ✅ |
| `POST_NOTIFICATIONS`（设置页引导） | 33 | `SettingsScreen.kt:221-228` | `if (SDK_INT >= TIRAMISU && !notificationGranted)` 才展示并 `permissionLauncher.launch(...)` | ✅ |
| `RECEIVE_BOOT_COMPLETED` | — | `BootReceiver` | 系统广播，无 API 级别问题 | ✅ |
| `VIBRATE` | — | `NotificationHelper.kt:54` `setVibrate`、渠道 `enableVibration(true)` | 已声明 | ✅ |

其他 API 级别敏感点：
- `NotificationChannels.createAll`：`if (SDK_INT < O) return` + `@RequiresApi(O)` 私有方法（`NotificationChannels.kt:29-62`）✅
- `alarmManager.canScheduleExactAlarms()`（API 31+）在 `SettingsScreen.kt:370-376` 同样有 `SDK_INT < S → true` 守卫 ✅
- `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` / `ACTION_APP_NOTIFICATION_SETTINGS`（API 31/26）仅在对应条件分支内构造 ✅
- `enableEdgeToEdge()`（activity-compose 1.9.3）、`installSplashScreen()`、`LifecycleEventEffect`（lifecycle 2.8.7 ≥ 2.7）均满足最低版本 ✅
- `PendingIntent` 全部带 `FLAG_IMMUTABLE`（API 23+ 必需）✅（`ReminderSchedulerImpl.kt:130`、`NotificationHelper.kt:83`）

**§5 结论：6 项权限、4 处 API 级别守卫、2 处 PendingIntent 可变性全部正确，未发现问题。**

---

## §6 Compose 正确性

**检查项：12 个 `@Composable` 页面 + 9 个共享组件 + 3 个 theme 文件；重点核对实验性 API 的 `@OptIn`、Canvas 手绘的数组越界、重组稳定性、状态提升。**

- **实验性 API `@OptIn` 核对**：`TopAppBar`（AppRoot ✅）、`SingleChoiceSegmentedButtonRow/SegmentedButton`（TrainScreen ✅、SettingsScreen ✅、AddEditHabitScreen ✅）、`ModalBottomSheet`（CheckInSheet ✅、TodayScreen ✅）、`combinedClickable`（ExerciseCheckCard ✅，`@OptIn(ExperimentalFoundationApi::class)`）。`FilterChip` 在 Material3 1.3（BOM 2024.12.01）已稳定，**无需** `@OptIn`（`BodyMetricsScreen/AddEditPlanScreen/AddEditExerciseScreen/TrainScreen/AddEditHabitScreen` 的用法正确，脚本对 3 个文件的"缺 OptIn"提示为误报）。
- **Canvas 手绘越界核对**：
  - `TrendChart`：`points[points.size/2]` 有 `size > 2` 守卫；`first()/last()` 有 `isNotEmpty()` 守卫；`maxOfOrNull ?: 0` 防空 ✅
  - `HeatmapGrid.buildColumns`：`cells.first()` 有 `isEmpty()` 早返回；`columns[slot/7][slot%7]` 的 `weekCount=ceil((offset+size)/7)` 可证不越界 ✅
  - `ProgressRing`：`total==0 → ratio=0` 且用 `coerceIn(0,1)`；`strokeWidth` 用 `minDimension` 计算防溢出 ✅
  - `CategoryPieChart` / `BodyMetricTrendChart`：空/单点均有分支保护 ✅
- **状态与重组**：`TodayScreen` 把 `var sheetItem by remember` 先取本地 `val currentSheetItem = sheetItem` 再判空（`TodayScreen.kt:152-153`）——这是**规避委托属性智能转换**的正确写法；`SnackbarHostState` 经 `staticCompositionLocalOf` 在 `AppRoot` 单例提供，`LocalSnackbarHostState.current` 由 9 个页面共用 ✅
- **主题**：`IronHabitTheme` 通过 `@EntryPoint` 读 `SettingsRepository`，`themeMode=null` 时跟随用户设置、显式传值时以传入为准；只用 `lightColorScheme/darkColorScheme` 两套（无动态取色）✅
- **未使用变量**：`TrendChart.kt` 曾有的 `emptyColor` 死代码（原 L2）已在 Round 2 删除 ✅。

**§6 结论：Compose 层在 Round 2 后未发现编译/崩溃级问题；`@OptIn`、Canvas 越界保护、状态提升均正确。**

---

## §7 零网络与硬编码复查

**检查项：依赖清单、清单权限、源码 URL/网络字面量、中文硬编码、`Dispatchers.IO`/`GlobalScope` 硬编码。**

- **依赖零网络**：`libs.versions.toml` 与 `app/build.gradle.kts` 全文扫描，未出现 `retrofit|okhttp|ktor|firebase|play-services|volley|glide|coil|picasso`；业务库仅 `kotlinx-serialization-json` / `kotlinx-datetime` / `kotlinx-coroutines-android`，全部纯本地 ✅
- **权限零网络**：Manifest 未声明 `android.permission.INTERNET`（甚至刻意不写出该字面量以避免 CI 误命中）✅
- **URL 字面量**：全工程仅 8 处 `http://schemas.android.com...` 命名空间声明（XML 必需，非网络库/请求）✅
- **`Dispatchers.IO` 硬编码**：仅 `di/AppModule.kt:23`、`:28` 两个 `@Provides`（唯一允许处）；其余全部走 `@IoDispatcher`/`@DefaultDispatcher` 注入 ✅
- **`GlobalScope`**：全工程 0 处 ✅；后台任务一律用 `@ApplicationScope CoroutineScope`（`IronHabitApp`、`ReminderReceiver`、`BootReceiver`）✅
- **`runBlocking`**：仅 androidTest（脚本确认 2 个文件）✅
- **中文硬编码**：main 源集中仅 3 处中文字面量，且均为**开发者向**（异常 message / `error()` 提示），不会直接展示给用户：
  - `data/repository/BackupRepositoryImpl.kt:80`（`require` 的异常 message，UI 侧最终映射为 `msg_import_failed`）
  - `domain/usecase/ImportDataUseCase.kt:36`（同上，`Result.failure` 的 message）
  - `ui/components/AppSnackbarHost.kt:11`（`error("SnackbarHostState 未提供")`，开发期断言）
  - 另：`data/preset/BuiltInExercises.kt` 的 48 个预置动作名/肌群为**架构允许的唯一例外** ✅
- **色值硬编码**：`Color(0xFF...)` 仅出现在 `ui/theme/Color.kt`（脚本确认唯一处）✅
- **数字输入**：`KeyboardType.Number/Decimal` + `toIntOrNull/toFloatOrNull` 校验，非法输入禁用保存并提示 `error_invalid_number` ✅

**§7 结论：零网络红线严守、无网络库/权限、无 `GlobalScope`/`Dispatchers.IO` 滥用、无用户可见中文硬编码。未发现问题。**

---

## §8 缺陷清单（Round 1 发现 · Round 2 全部关闭）

> 每条均给出 `相对路径:行号`。路径根为 `fitness-app/`。**状态列标注 Round 2 复核结果。**

### HIGH（阻断编译）

#### C1 — `TrendChart.kt` 使用 `Color` 但未导入 —— ✅ 已修复
- **位置**：`app/src/main/java/com/ironhabit/app/ui/components/TrendChart.kt:41`（同时命中 `:42`、`:43`）
- **现象**：`val barColor: Color = colorScheme.primary` 等 3 行引用 `Color`，但文件 import 段（1–23 行）**没有** `import androidx.compose.ui.graphics.Color`。
- **原因**：`Color` 在同一包 `ui.components` 下无同名声明，也无通配 import → 编译器报 `Unresolved reference: Color`（3 处）。
- **修复**：在 import 段加入 `import androidx.compose.ui.graphics.Color`。**Round 2 已在 `TrendChart.kt:19` 确认该 import 存在。**

#### C2 — `TodayViewModel` 用 `mapLatest` 产出 `Flow<Flow<T>>` 后按元素类型消费 —— ✅ 已修复
- **位置**：`app/src/main/java/com/ironhabit/app/ui/screens/today/TodayViewModel.kt:66`（配合 `:67`）
- **现象**：`checkInRepository.observeActiveDaysSince(...).mapLatest { getTodayOverview(todayEpochDay()) }` —— `getTodayOverview(...)` 返回 `Flow<TodayOverview>`，`mapLatest` 不做扁平化，故该链为 `Flow<Flow<TodayOverview>>`；紧接着 `:67` 的 `.map { overview: TodayOverview -> overview.toUiState() }` 期望元素类型是 `TodayOverview`，与实际 `Flow<TodayOverview>` 不符。
- **原因**：`mapLatest` 应改为扁平化的 `flatMapLatest`（`import` 已在 `:24` 存在）。
- **修复**：把 `TodayViewModel.kt:66` 的 `.mapLatest {` 改成 `.flatMapLatest {`。**Round 2 已在 `TodayViewModel.kt:66` 确认改为 `flatMapLatest`。**

#### C3 — 在"委托属性"上做智能转换（11 处，全部编译失败）—— ✅ 已修复
- **位置（条件行 → 使用行，Round 1）**：
  1. `ui/screens/today/TodayScreen.kt:91 → 93`
  2. `ui/screens/train/TrainScreen.kt:114 → 116`
  3. `ui/screens/discipline/DisciplineScreen.kt:67 → 69`
  4. `ui/screens/profile/ProfileScreen.kt:78 → 80`
  5. `ui/screens/history/HistoryScreen.kt:61 → 62`
  6. `ui/screens/bodymetrics/BodyMetricsScreen.kt:88 → 89`
  7. `ui/screens/settings/SettingsScreen.kt:122 → 123`
  8. `ui/screens/exercise/AddEditExerciseScreen.kt:87 → 89`
  9. `ui/screens/exercise/ExerciseDetailScreen.kt:57 → 58`
  10. `ui/screens/plan/AddEditPlanScreen.kt:86 → 88`
  11. `ui/screens/habit/AddEditHabitScreen.kt:104 → 106`
- **现象**：每个文件都在 `val uiState by viewModel.uiState.collectAsStateWithLifecycle()`（委托属性）后写 `when { ... uiState.errorRes != null -> { ... stringResource(uiState.errorRes) ... } }`。`uiState.errorRes` 为 `Int?`，`stringResource(id: Int)` 需要 `Int`，依赖智能转换把 `Int?` 收窄为 `Int`。
- **原因**：Kotlin 语言规范禁止对**局部委托属性**做智能转换（官方原文：*"val local variables — Always, **except local delegated properties**"*；Android 官方文档亦明确 *"Kotlin cannot smart-cast a by-delegated property directly"*）。因此编译器报 `Smart cast to 'Int' is impossible...` / `Type mismatch: inferred type is Int? but Int was expected`。
- **修复**：在分支内先取本地 val：`val errorRes: Int? = uiState.errorRes`，再 `errorRes != null -> stringResource(errorRes)`。**Round 2 已在全部 11 个文件确认采用该写法**（行号见 §10.1）。

### HIGH（必崩运行时）

#### C4 — `msg_streak_up` 的 `%1$d` 收到 String 参数 → `IllegalFormatConversionException` —— ✅ 已修复
- **位置**：`ui/screens/today/TodayViewModel.kt:217`（构造参数）+ `ui/screens/today/TodayUiState.kt:36`（`snackbarArgs: List<String>`）+ 消费点 `ui/screens/today/TodayScreen.kt:60-62`（`stringResource(res, *uiState.snackbarArgs.toTypedArray())`）
- **现象**：`snackbarArgs = if (streakRes == R.string.msg_streak_up) listOf(current.toString()) else ...`。`current` 是 `Int`，转成 `String` 后传入；而 Round 1 的 `strings.xml:51` 为 `msg_streak_up` = `连续 %1$d 天 🔥`。`Resources.getString(id, args)` 内部执行 `String.format("…%1$d…", "3")`。
- **原因**：Java `Formatter` 的 `%d` 只接受整型，收到 `String` 抛 `java.util.IllegalFormatConversionException: d != java.lang.String` → **首页在一键打卡使连击 +1 时崩溃**（`applyData` 检测 `current > previous` 即触发，首次打卡即命中）。
- **修复**：把 `strings.xml:51` 的 `msg_streak_up` 占位符由 `%1$d` 改为 `%1$s`。**Round 2 已在 `strings.xml:51` 确认占位符为 `%1$s`，与 `TodayViewModel.kt:217` 的 `List<String>` 匹配。**

### MEDIUM（UX / 无障碍语义）

#### M1 —"动作详情"图标的行为与语义不符，且存在重复入口 —— ✅ 已修复
- **位置（Round 1）**：`ui/screens/today/TodayScreen.kt:122-123`（`onOpenDetail = { sheetItem = item }` 与 `onOpenSheet = { sheetItem = item }` 传同一 lambda）配合 `ui/components/ExerciseCheckCard.kt:47`（KDoc "详情图标 → onOpenDetail"）与 `:119-124`（图标 `contentDescription = R.string.title_exercise_detail` 即"动作详情"）。
- **现象**：卡片右侧"描述(Description)"图标的无障碍文案是"动作详情"，但点击后打开的是"补录详情"底部弹层（`CheckInSheet`），与文案承诺的行为不一致；且与上方"补录详情"按钮行为完全重复，`onOpenDetail` 形同虚设。
- **原因**：`TodayScreen` 没有"打开动作详情"的导航回调（`AppRoot` 也未向 Today 注入 `onOpenExercise`），于是两处都退化为打开弹层。
- **修复**：`TodayScreen` 新增 `onOpenExerciseDetail: (Long) -> Unit = {}`（`TodayScreen.kt:53`），`onOpenDetail`（`:125`）→ `onOpenExerciseDetail(item.exercise.id)`；`IronHabitNavGraph.kt:47-49` 注入 `navController.navigate(Destinations.exerciseDetail(exerciseId))`。**Round 2 已确认链路闭合**（见 §10.1）。

### LOW

#### L1 — `HabitRepository` 接口 KDoc 与实现语义不符 —— ✅ 已修复
- **位置**：`domain/repository/HabitRepository.kt:32-33`
- **现象**：注释写"观察某习惯**全部有记录**的日期，降序"，但实现（`data/repository/HabitRepositoryImpl.kt:69-70` → `HabitLogDao.observeActiveDays`，`HabitLogDao.kt:28`）只返回 `is_completed = 1` 的日期。
- **判定**：**实现正确**（streak 只统计完成日），仅注释误导。
- **修复**：注释改为"观察某习惯**已勾选完成**的日期（`is_completed = 1`）"。**Round 2 已在 `HabitRepository.kt:32-38` 确认更新。**

#### L2 — 死代码：`TrendChart` 中 `emptyColor` 声明后未使用 —— ✅ 已修复
- **位置**：`ui/components/TrendChart.kt:42`
- **现象**：`val emptyColor: Color = colorScheme.surfaceVariant` 从未被读取（编译器 warning），且它本身就是 C1 缺 import 的引用点之一。
- **修复**：删除该行。**Round 2 已在 `TrendChart.kt` 确认该行已移除（现仅 `barColor`/`baselineColor`）。**

### INFO（非缺陷，记录备查）

- **I1 未引用字符串 13 个**：`action_backfill`、`action_checkin`、`action_close`、`action_share`、`action_start`、`action_undo_checkin`、`app_slogan`、`category_all`、`cd_checkin_undone`、`label_optional`、`title_progress`、`title_week_plan_by_day`（另 `app_name` 由 Manifest 引用，非未用）。无功能影响。
- **I2 `MainDispatcherRule` 的调度器与 `runTest` 不同源**：`test/.../MainDispatcherRule.kt:23` 用裸 `StandardTestDispatcher()`（新 scheduler），而 `runTest` 自带 scheduler。因本案 4 个用它的用例都在 `runTest` 中直接调用 suspend 函数（未真正切到 `Dispatchers.Main`），不影响结果。
- **I3 `DatabaseModule` 的 DAO `@Provides` 未加 `@Singleton`**：因只被单例仓库注入，无功能影响。

---

## §9 不可验证项与残余风险（交付须知）

1. **编译未验证**：本环境无 JDK / Kotlin 编译器 / Android SDK / Gradle 缓存，未执行任何 Gradle 任务。§8 中 C1/C2/C3 是**基于 Kotlin 语言规范的静态推演**（C1 为符号不可解析、C2 为类型不匹配、C3 有官方文档明文支撑），置信度高，但**必须由一次 `./gradlew compileDebugKotlin` 或 CI 确证**。
2. **测试未执行**：26 个用例均只做人工推演（§3），未实际运行；未生成覆盖率报告。
3. **未安装/未启动**：无法验证冷启动、启动图、首启播种（`BuiltInExercises` 48 条幂等）、Room 建库与迁移降级路径。
4. **通知未实测**：`AlarmManager` 精确/降级闹钟、`ReminderReceiver` 触发与"明天同一时刻"自续期、`BootReceiver` 重排、通知渠道与震动，均未在真机验证；国产 ROM 后台限制行为不可静态判定。
5. **UI 交互未实测**：点击/长按/弹层、Tab 状态恢复（`navigateToTab` 的 `setPopUpTo/setLaunchSingleTop/setRestoreState`）、返回栈、深浅色切换、旋转/进程重建后的状态保持，均未验证。
6. **Hilt/KSP/Room/R8 产物未生成**：未验证 `@HiltAndroidApp` 组件生成、DAO 实现生成、`app/schemas/1.json`、release 混淆后可用性。
7. **日期口径残余风险**：全部按天计算依赖 `kotlinx-datetime 0.6.1` 的 `toEpochDays()/atStartOfDayIn/toLocalDateTime` + 注入 `Clock/TimeZone`；跨时区旅行、夏令时边界、设备改时间、`streak` 的"今天/昨天"判定与热力图首尾两天，只做静态推演，需真机兜底。
8. **签名与发布**：`app/build.gradle.kts:39-49` 仅在存在 `keystore.properties` 时创建 release 签名，否则产物为**未签名 APK**——CI 若未注入 keystore，`assembleRelease` 产物不可直接安装（非缺陷，属发布流程须知）。
9. **字符串占位符**：本次只对"带参数"的 5 处 `stringResource` 做了类型配对（§3.3），其余 158 处为无参调用；将来新增带参调用需回归 §3.3 的检查。

> **Round 1 修复优先级建议**：先修 C3（11 处、机械改法）→ C2 → C1 → C4，再进第二轮回归（Round 2）。**该流程已执行完毕，见 §10。C1/C2/C3 修完后仍需真实执行一次 `compileDebugKotlin` 才能确证"可编译"，本报告不代偿此步。**

---

## §10 Round 2 回归验证（修复复核）

**背景**：Round 1 判定 FAIL（7 项缺陷）。software-engineer 于任务 #11 完成修复后，QA 重新以全新视角通读当前工作树（133 个 `.kt` 全量 + `strings.xml` + 导航图），逐条复核每项缺陷，并专项复查"是否引入回归"。

### 10.1 逐项复核（7/7 关闭）

| 缺陷 | Round 1 位置 | 修复后现状（Round 2 证据） | 状态 |
|---|---|---|---|
| **C1** 缺 `Color` import | `ui/components/TrendChart.kt:41-43` | `TrendChart.kt:19` 已新增 `import androidx.compose.ui.graphics.Color`；`:42/:43`（`barColor`/`baselineColor`）引用可解析 | ✅ 关闭 |
| **C2** `mapLatest` 应为 `flatMapLatest` | `ui/screens/today/TodayViewModel.kt:66` | `:66` 已改为 `.flatMapLatest { getTodayOverview(todayEpochDay()) }`，`:67` 的 `.map { overview: TodayOverview -> ... }` 元素类型吻合；全仓 `mapLatest` 仅剩 `:26` 未用 import 与 `:37` KDoc（见 N1/N2） | ✅ 关闭 |
| **C3** 委托属性智能转换（11 处） | 11 个 Screen 的 `when { errorRes != null -> stringResource(errorRes) }` | 11 个文件全部改为"先取本地 val 再判空"：`TodayScreen:86`、`TrainScreen:108`、`DisciplineScreen:61`、`ProfileScreen:72`、`HistoryScreen:56`、`BodyMetricsScreen:83`、`SettingsScreen:117`、`AddEditExerciseScreen:82`、`ExerciseDetailScreen:52`、`AddEditPlanScreen:81`、`AddEditHabitScreen:99`（均为 `val errorRes: Int? = uiState.errorRes`） | ✅ 关闭 |
| **C4** `%1$d` 收到 String | `strings.xml:51` + `TodayViewModel.kt:217` | `strings.xml:51` 占位符已改为 `%1$s`（`连续 %1$s 天 🔥`），与 `TodayViewModel.kt:217` 的 `listOf(current.toString())`（`List<String>`）类型匹配，`IllegalFormatConversionException` 消除 | ✅ 关闭 |
| **M1** 图标语义不符 + 重复入口 | `ui/screens/today/TodayScreen.kt:122-123` | `TodayScreen` 新增形参 `onOpenExerciseDetail: (Long) -> Unit = {}`（`:53`），`:125` 接 `onOpenDetail = { onOpenExerciseDetail(item.exercise.id) }`，`:126` `onOpenSheet = { sheetItem = item }` 保持打开补录弹层——两者不再重复；`IronHabitNavGraph.kt:47-49` 注入 `navController.navigate(Destinations.exerciseDetail(exerciseId))`；`Destinations.exerciseDetail()`（`Destinations.kt:33`）→ 路由 `exercise/detail/{exerciseId}`（`IronHabitNavGraph.kt:109-120`）→ `ExerciseDetailViewModel` 以 `Destinations.EXERCISE_DETAIL_ARG` 读取参数（`ExerciseDetailViewModel.kt:60-61`），**键名一致、链路闭合** | ✅ 关闭 |
| **L1** KDoc 与实现不符 | `domain/repository/HabitRepository.kt:32-33` | KDoc 已改为"观察某习惯全部**已完成**的日期（`is_completed = 1`）"，并补充"表中有记录 ≠ 已完成"的说明，与实现一致 | ✅ 关闭 |
| **L2** 死代码 `emptyColor` | `ui/components/TrendChart.kt:42` | 该行已删除，`TrendChart.kt` 现仅保留 `barColor`/`baselineColor` 两个实际使用的颜色变量 | ✅ 关闭 |

### 10.2 回归复查（未发现新缺陷）

- **同族风险扩查**：对全仓 `ui/**` 中"`!= null` 后接非空消费"的写法做了穷举（`grep -n '!= null' ui/**`），确认除已修的 11 处 `errorRes` 外，其余为：局部 val（`snackbarText` / `currentSheetItem` / `uri` / `validSets` / `validReps`）、lambda 形参（`BackupScreen` 的 `uri`）、函数形参（`EmptyState` / `SettingsScreen` 的 `actionText`/`onAction`），以及**仅作布尔判定、不下钻使用**的 `uiState.nameErrorRes != null` / `numberErrorRes != null`（传给 `isError: Boolean`，**不触发智能转换**）——**均合法，无遗漏**。
- `CheckInSheet.kt:51-59` 的 `sets/reps/weight/duration` 是**局部 val**（非委托属性），`sets != null && sets > 0` 等智能转换合法；`:146-148` 亦先落 `validSets/validReps` 局部 val。**无同类隐患**。
- **导航图签名一致**：`TodayScreen` 新增形参带默认值 `{}`，`IronHabitNavGraph` 显式传参；`AppRoot` 仅整体挂载 `IronHabitNavGraph`，无其他调用点需同步。
- **测试未受影响**：`app/src/test` 6 类 18 用例 + `app/src/androidTest` 2 类 8 用例与 Round 1 完全一致（修复仅动 main 源）；Round 1 §3 的 26/26 人工推演结论继续成立；全仓无 `assertTrue(true)` / `Thread.sleep` / `@Ignore` / `TODO(`。

### 10.3 遗留项（**非阻断**，warning 级，不影响编译与运行）

- **N1**：`ui/screens/today/TodayViewModel.kt:26` 的 `import kotlinx.coroutines.flow.mapLatest` 在 C2 修复后已无引用（编译器 unused-import warning）。建议删除。
- **N2**：`ui/screens/today/TodayViewModel.kt:37` 的 KDoc 仍写 `mapLatest { getTodayOverview(今天) }`，与实现（`:66` 已为 `flatMapLatest`）不符（仅文档陈旧）。建议同步为 `flatMapLatest`。

> N1/N2 均为**非阻断**的整洁性问题，不改变"可交付"判定。

### 10.4 最终判定

**PASS（可交付）**：Round 1 的 4 HIGH + 1 MEDIUM + 2 LOW **已 100% 关闭**，回归复查未发现新增缺陷；仅余 2 项 warning 级整洁性遗留（N1/N2）。

> 与 Round 1 相同的环境限制仍然成立：本沙箱**无 JDK/Android SDK/Gradle 缓存/模拟器**，未实际执行 `compileDebugKotlin` / `testDebugUnitTest` / `assembleDebug` / `connectedAndroidTest`。**建议交付前由 CI 跑一次 `./gradlew assembleDebug testDebugUnitTest` 作为最终确证。**
