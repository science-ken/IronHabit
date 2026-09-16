package com.ironhabit.app.ui.screens.today

import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealTotals
import com.ironhabit.app.domain.model.StreakInfo
import com.ironhabit.app.domain.model.TodayPlanItem

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
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val trainingStreak: StreakInfo = StreakInfo(0, 0, null),
    val isRestDay: Boolean = false,
    val plannedWeekdays: List<Int> = emptyList(),
    val todayEpochDay: Long = 0L,
    val errorRes: Int? = null,
    val snackbarRes: Int? = null,
    val snackbarArgs: List<String> = emptyList(),
)
