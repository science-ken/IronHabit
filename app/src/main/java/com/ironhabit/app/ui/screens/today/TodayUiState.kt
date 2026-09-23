package com.ironhabit.app.ui.screens.today

import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealIntake
import com.ironhabit.app.domain.model.MealItem
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
    /**
     * 所选日**已记下的条目**（`meal_items`）。
     *
     * ⚠️ 与 [meals] 是两件事：`meals` 里可以是 AI 排的计划，
     * 这个列表里有行才代表"真的吃了"。混用就等于把"排了课"当"练了"。
     */
    val mealItems: List<MealItem> = emptyList(),
    /** 所选日的实际摄入（明细优先，其次打勾的整餐值）。见 [MealIntakeCalculator]。 */
    val mealIntake: MealIntake = MealIntake(0, 0.0, 0.0, 0.0, 0, emptySet()),
    /** 正在为哪一餐挑食物；非 `null` 时渲染食物库挑选弹层。 */
    val pickingMealId: Long? = null,
    /** 正在改份量的那条条目；非 `null` 时渲染条目编辑弹层。 */
    val editingItemId: Long? = null,
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
    /** 「已算出一份预览」的一次性信号：界面据此跳到预览页，消费后立即置回 `false`。 */
    val previewRequested: Boolean = false,
    /**
     * 「以后每周都用这份」那份模板里的**启用**行数。
     *
     * `0` = 没有模板在生效 → 界面那一行整行不显示（不画一个"停用"按钮去解释一件没发生的事）。
     * 它不是开关状态：模板是"哪些周会回落它"的结果，与本周复制过什么无关。
     */
    val repeatPlanRowCount: Int = 0,
    /**
     * 「复制到下周」的确认待决态（`null` = 没有待确认）。
     *
     * 只在**下周已经有自己的行**时出现：复制是并集 + 同槽位覆盖内容，
     * 而本应用的规矩是"覆盖用户数据前必确认"（与身体数据删除、导入确认框同源）。
     * 值 = 下周现有的启用行数，念进确认框正文。
     */
    val copyToNextWeekConfirm: Int? = null,
    /** 正在复制或停用（按钮禁用，避免连点写两遍）。 */
    val isRepeatActionBusy: Boolean = false,
    /**
     * 「复制到下周 / 停用」的回执，画在**弹层内部**。
     *
     * ⚠️ 不走全局 Snackbar：本应用的 §7 坑 3 —— `ModalBottomSheet` 会把 Snackbar 整个盖住，
     * 用户点了按钮什么也看不见（本轮真机复现：写库成功、界面零反馈）。
     */
    val repeatNoteRes: Int? = null,
    val repeatNoteArgs: List<String> = emptyList(),
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
