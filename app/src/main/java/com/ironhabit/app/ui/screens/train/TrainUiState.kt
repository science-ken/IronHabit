package com.ironhabit.app.ui.screens.train

import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.WeekPlan

/**
 * 训练页历史列表项：某天的打卡条数。
 *
 * @property epochDay 日期口径
 * @property count 当天打卡条数
 */
data class HistoryEntry(
    val epochDay: Long,
    val count: Int,
)

/**
 * 「训练」页 UI 状态（不可变）。
 *
 * @property isLoading 加载中
 * @property selectedDay 当前选中星期（`1` = 周一 … `7` = 周日）
 * @property plans 所选星期的计划条目
 * @property exercises 动作（动作库；v6 起动作无"停用"概念，全量可见）
 * @property exerciseNameById `exerciseId → name` 查表（计划列表展示动作名用）
 * @property history 近 30 天打卡历史（按日期倒序）
 * @property plannedDaysByExercise **本周生效计划**里 `exerciseId → 出现的星期集合`（动作库「已加入」✓ 的数据源）
 * @property repeatDaysByExercise **「每周相同」那份**里 `exerciseId → 出现的星期集合`（弹层勾选初值参考）
 * @property addToPlanSheetExercise 非 `null` = 正在为该动作打开「加入计划」弹层
 * @property isSubmittingAdd 正在写库（弹层确认按钮禁用，防连点）
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性 Snackbar 资源 id
 */
data class TrainUiState(
    val isLoading: Boolean = true,
    val selectedDay: Int = 1,
    val plans: List<WeekPlan> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
    val exerciseNameById: Map<Long, String> = emptyMap(),
    val history: List<HistoryEntry> = emptyList(),
    val plannedDaysByExercise: Map<Long, Set<Int>> = emptyMap(),
    val repeatDaysByExercise: Map<Long, Set<Int>> = emptyMap(),
    val addToPlanSheetExercise: Exercise? = null,
    val isSubmittingAdd: Boolean = false,
    val errorRes: Int? = null,
    val snackbarRes: Int? = null,
)
