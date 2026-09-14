package com.ironhabit.app.domain.model

/**
 * 连续打卡（streak）结果模型。
 *
 * @property current 当前连续天数
 * @property best 历史最长连续天数（永久保留）
 * @property lastActiveEpochDay 最近一次活跃日期，可空（从未打卡时为 `null`）
 */
data class StreakInfo(
    val current: Int = 0,
    val best: Int = 0,
    val lastActiveEpochDay: Long? = null,
)
