package com.ironhabit.app.ui.screens.stats

import com.ironhabit.app.domain.model.CategoryShare
import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.model.TrendPoint

/**
 * 「训练统计」页 UI 状态（不可变）。
 *
 * @property isLoading 加载中
 * @property days 当前区间（`7` / `30` / `90`），三块图共用同一个值
 * @property trend 区间内每日打卡次数
 * @property categoryShare 区间内训练类型占比
 * @property heatmap 区间内热力图格子
 * @property errorRes 页面级错误资源 id
 */
data class TrainingStatsUiState(
    val isLoading: Boolean = true,
    val days: Int = TrainingStatsViewModel.DEFAULT_DAYS,
    val trend: List<TrendPoint> = emptyList(),
    val categoryShare: List<CategoryShare> = emptyList(),
    val heatmap: List<HeatmapCell> = emptyList(),
    val errorRes: Int? = null,
)
