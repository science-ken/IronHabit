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
 * @property exercises 启用动作（动作库）
 * @property disabledExercises 已停用动作（`isActive = false`）→ 动作库「已停用」分组，可一键恢复
 * @property exerciseNameById `exerciseId → name` 查表（计划列表展示动作名用）
 * @property history 近 30 天打卡历史（按日期倒序）
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性 Snackbar 资源 id
 */
data class TrainUiState(
    val isLoading: Boolean = true,
    val selectedDay: Int = 1,
    val plans: List<WeekPlan> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
    val disabledExercises: List<Exercise> = emptyList(),
    val exerciseNameById: Map<Long, String> = emptyMap(),
    val history: List<HistoryEntry> = emptyList(),
    val errorRes: Int? = null,
    val snackbarRes: Int? = null,
)
