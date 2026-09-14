package com.ironhabit.app.ui.screens.discipline

import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.domain.model.HeatmapCell

/**
 * 「自律」页 UI 状态（不可变）。
 *
 * @property isLoading 加载中
 * @property dateEpochDay 今日日期口径（习惯勾选写库时使用）
 * @property habits 启用习惯（含今日完成状态与连续天数）
 * @property heatmap 近 90 天热力图单元格（升序）
 * @property monthCompletionRate 本月完成率（`0f..100f`）
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性 Snackbar 资源 id
 */
data class DisciplineUiState(
    val isLoading: Boolean = true,
    val dateEpochDay: Long = 0L,
    val habits: List<HabitItem> = emptyList(),
    val heatmap: List<HeatmapCell> = emptyList(),
    val monthCompletionRate: Float = 0f,
    val errorRes: Int? = null,
    val snackbarRes: Int? = null,
)
