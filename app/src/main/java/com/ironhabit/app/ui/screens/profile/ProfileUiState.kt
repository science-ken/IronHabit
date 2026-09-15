package com.ironhabit.app.ui.screens.profile

import com.ironhabit.app.domain.model.CategoryShare
import com.ironhabit.app.domain.model.TrendPoint
import com.ironhabit.app.domain.model.UserProfile

/**
 * 「我的」页 UI 状态（不可变）。
 *
 * @property isLoading 加载中
 * @property trend 近 30 天打卡趋势
 * @property categoryShare 训练类型占比
 * @property profile 用户档案（供「身体档案」概要卡展示）
 * @property errorRes 页面级错误资源 id
 */
data class ProfileUiState(
    val isLoading: Boolean = true,
    val trend: List<TrendPoint> = emptyList(),
    val categoryShare: List<CategoryShare> = emptyList(),
    val profile: UserProfile = UserProfile(),
    val errorRes: Int? = null,
)
