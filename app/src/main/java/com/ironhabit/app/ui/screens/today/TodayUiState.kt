package com.ironhabit.app.ui.screens.today

import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealTotals
import com.ironhabit.app.domain.model.StreakInfo
import com.ironhabit.app.domain.model.TodayPlanItem
import com.ironhabit.app.domain.model.WeeklyReview

/**
 * 「今日」页 UI 状态（不可变）。
 *
 * 文案一律用**资源 id**（[errorRes] / [snackbarRes]）承载，避免在 UiState 中硬编码中文字符串，
 * 由 Compose 侧 `stringResource(...)` 解析（架构 §7.5）。
 *
 * @property isLoading 首帧加载中（显示骨架屏）
 * @property dateEpochDay 今日日期口径（`LocalDate.toEpochDays()`）
 * @property plans 今日训练项
 * @property habits 今日习惯项
 * @property meals 今日饮食餐列表（v3）
 * @property mealTotals 今日饮食合计（已摄入 / 计划，v3）
 * @property dietTarget 今日营养目标（规则现算，不落库，v3）
 * @property editingMeal 正在编辑的那一餐（`null` = 未打开编辑弹层）
 * @property completedCount 已完成项数（训练 + 习惯）
 * @property totalCount 总项数
 * @property trainingStreak 训练连续打卡信息（进度环大字）
 * @property isRestDay 今日无计划也无习惯（显示休息日提示）
 * @property plannedWeekdays 「有计划的日子」（`1..7`，升序）→ 日期栏 chip 行（v2）
 * @property todayEpochDay 真正的「今天」（用于日期栏高亮，与 [dateEpochDay] 游标区分）
 * @property errorRes 页面级错误文案资源 id，`null` 表示无错误
 * @property snackbarRes 一次性 Snackbar 文案资源 id，用后置 `null`
 * @property snackbarArgs Snackbar 文案的格式化参数。
 *   ⚠️ **本通道实参恒为 `String`** → 配套资源占位符必须用 `%1$s`，**不可用 `%1$d`**
 *   （否则 `stringResource` 内部 `String.format` 抛 `IllegalFormatConversionException`，见
 *   `StringResourcePlaceholderContractTest`）。
 */
data class TodayUiState(
    val isLoading: Boolean = true,
    val dateEpochDay: Long = 0L,
    val plans: List<TodayPlanItem> = emptyList(),
    val habits: List<HabitItem> = emptyList(),
    val meals: List<Meal> = emptyList(),
    val mealTotals: MealTotals = MealTotals(),
    val dietTarget: DietTarget = DietTarget(),
    /** 正在编辑的那一餐；非 `null` 时页面渲染编辑弹层（未来日只读态不渲染）。 */
    val editingMeal: Meal? = null,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val trainingStreak: StreakInfo = StreakInfo(0, 0, null),
    val isRestDay: Boolean = false,
    val plannedWeekdays: List<Int> = emptyList(),
    /**
     * 选中那一天**所在周的周一**（P3：计划按周存放）。
     *
     * 页面用它显示"你正在看哪一周"，也是「让 AI 生成」的目标周。
     */
    val selectedWeekStartEpochDay: Long = 0L,
    /**
     * **这一周**到底有没有计划（不是"今天有没有"）。
     *
     * 用来区分两种"今天没动作"：
     * - `false` → 这一周还没排课 → 显示「创建训练计划」（自己创建 / 让 AI 生成）；
     * - `true`  → 只是今天是休息日 → 显示"今天是休息日，好好放松"。
     */
    val hasPlanThisWeek: Boolean = false,
    /** 正在为这一周生成训练计划（按钮禁用 + 文案更换）。 */
    val isCreatingPlan: Boolean = false,
    /**
     * 「每周相同」是否已开启（= 存在那份"以后每周都用这份"的计划）。
     *
     * 语义：没有单独排计划的周，都会显示这一份。
     */
    val isRepeatWeeklyOn: Boolean = false,
    /** 正在切换「每周相同」（开关禁用，避免连点）。 */
    val isTogglingRepeatWeekly: Boolean = false,
    val todayEpochDay: Long = 0L,
    /**
     * 所选日**所在周**的复盘（喂给「本周」与「体重变化」两块磁贴）。
     *
     * `null` = 未加载或取数失败 → 对应磁贴**整块不渲染**。这里不用 `null→0` 兜底：
     * `0` 会被读成"这周没练"，那是编出来的结论（与 [WeeklyReview] 的诚实边界同一口径）。
     */
    val weeklyReview: WeeklyReview? = null,
    /**
     * 所选日**所在周**的热力（周一 → 周日，恒 7 格，没打卡的天 `level = 0`）。
     *
     * 空列表 = 未加载或取数失败 → 热力条整条不渲染。
     */
    val weekHeatmap: List<HeatmapCell> = emptyList(),
    /**
     * 所选日**所在周**计划要做的总组数 —— 「总组数 24 / 35」的分母。
     *
     * 与 [weeklyReview] 不同，**`0` 是真答案**（这周没排课），不是"没取到数"：
     * 它由生效计划行直接求和得到，没排课就是 0。为 0 时磁贴只显示完成数、不挂分母。
     */
    val plannedSetsThisWeek: Int = 0,
    val errorRes: Int? = null,
    val snackbarRes: Int? = null,
    val snackbarArgs: List<String> = emptyList(),
)
