package com.ironhabit.app.ui.screens.profile

import com.ironhabit.app.domain.model.CategoryShare
import com.ironhabit.app.domain.model.TrendPoint

/**
 * 「我的」页 UI 状态（不可变）。
 *
 * @property isLoading 加载中
 * @property trend 近 30 天打卡趋势
 * @property categoryShare 训练类型占比
 * @property errorRes 页面级错误资源 id
 */
data class ProfileUiState(
    val isLoading: Boolean = true,
    val trend: List<TrendPoint> = emptyList(),
    val categoryShare: List<CategoryShare> = emptyList(),
    val errorRes: Int? = null,
)
