package com.ironhabit.app.ui.screens.profile

import com.ironhabit.app.domain.model.BodyMetric
import com.ironhabit.app.domain.model.UserProfile

/**
 * 「我的」页 UI 状态（不可变）。
 *
 * 首屏那四个数字（[trainingStreak] / [weekCompletedDays] + [weekPlannedDays] /
 * [totalCheckIns] / [latestWeight]）的**口径全部与今日页一致**：
 * 连续走 `TodayOverview.trainingStreak`，分母走 `TodayOverview.plannedWeekdays`。
 * 不在这里重算一遍，是为了不让「我的」页和今日页对同一周说出两个数
 * （AI 复盘页的分母与今日页**刻意不同**，那是另一件事，见 `BuildWeeklyReviewUseCase`）。
 *
 * 两张统计图不在这里 —— 它们在「训练统计」页自己的状态里。
 *
 * @property isLoading 加载中
 * @property profile 用户档案（供档案卡与完整度环展示）
 * @property trainingStreak 当前连续打卡天数（应做日感知：休息日不断档）
 * @property weekCompletedDays 本周**有打卡**的天数
 * @property weekPlannedDays 本周**排了课**的天数；`0` = 这周没排课，界面不该显示「0/0」
 * @property totalCheckIns 装机以来的打卡条数（不是天数）
 * @property latestWeight 最新一条体重记录；`null` = 从没记过体重
 * @property errorRes 页面级错误资源 id
 */
data class ProfileUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile = UserProfile(),
    val trainingStreak: Int = 0,
    val weekCompletedDays: Int = 0,
    val weekPlannedDays: Int = 0,
    val totalCheckIns: Int = 0,
    val latestWeight: BodyMetric? = null,
    val errorRes: Int? = null,
)
