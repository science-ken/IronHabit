package com.ironhabit.app.domain.model

/**
 * 习惯频率。
 *
 * - [DAILY] 每天
 * - [WEEKLY] 每周（由 `weeklyDaysMask` 指定具体星期）
 */
enum class HabitFrequency {
    DAILY,
    WEEKLY,
}

/**
 * 领域模型：习惯定义。
 *
 * @property id 主键，自增；`0` 表示尚未落库
 * @property name 习惯名
 * @property emoji 图标（emoji）
 * @property colorHex 主题色 `#RRGGBB`
 * @property frequency 频率
 * @property weeklyDaysMask 星期掩码：bit0 = 周一 … bit6 = 周日（`0x7F` = 全周）
 * @property reminderEnabled 是否提醒
 * @property reminderHour 提醒小时，可空（未设置时为空）
 * @property reminderMinute 提醒分钟，可空（未设置时为空）
 * @property isActive 是否启用（`false` = 停用）
 * @property sortOrder 展示排序
 * @property createdAt 创建时间戳（UTC 毫秒）
 */
data class Habit(
    val id: Long = 0L,
    val name: String = "",
    val emoji: String = "\u2705",
    val colorHex: String = "#2196F3",
    val frequency: HabitFrequency = HabitFrequency.DAILY,
    val weeklyDaysMask: Int = 0x7F,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int? = null,
    val reminderMinute: Int? = null,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
) {
    companion object {
        /** 全周掩码：bit0..bit6 全为 1。 */
        const val WEEKLY_DAYS_ALL: Int = 0x7F
    }
}
