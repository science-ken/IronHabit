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
    /**
     * 有没有记过任何一次打卡。
     *
     * `monthCompletionRate == 0f` 单独看是歧义的（"本月 0 天"还是"这人还没开始用"），
     * 而后者该显示「还没有数据」—— 见本仓库反复立的那条「不用 0 冒充 null」。
     */
    val hasAnyCheckIn: Boolean = false,
    val errorRes: Int? = null,
    val snackbarRes: Int? = null,
)
