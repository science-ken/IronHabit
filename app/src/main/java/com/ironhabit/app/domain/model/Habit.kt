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
    val note: String? = null,
    /** 目标数值。`null` = 纯勾选型习惯；非 `null` = 计量型（如每天 8 杯水）。 */
    val targetValue: Double? = null,
    /** 目标单位文案（杯 / 分钟 / 步）。 */
    val targetUnit: String? = null,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
) {
    /**
     * 「应做日」星期集合（`1` = 周一 … `7` = 周日）；`null` = **每天都应做**。
     *
     * 供 [com.ironhabit.app.domain.util.StreakCalculator] 的应做日感知计数使用：
     * - [HabitFrequency.DAILY] → `null`（每天都算，旧口径）；
     * - [HabitFrequency.WEEKLY] → 由 [weeklyDaysMask] 展开（bit0 = 周一）；
     *   掩码为空（理论不可达，UI 会把空掩码兜成全周）时同样返回 `null`。
     */
    val expectedWeekdays: Set<Int>?
        get() {
            if (frequency != HabitFrequency.WEEKLY) return null
            val weekdays = (FIRST_WEEKDAY..LAST_WEEKDAY)
                .filter { day -> (weeklyDaysMask shr (day - FIRST_WEEKDAY)) and 1 == 1 }
                .toSet()
            return weekdays.takeIf { it.isNotEmpty() }
        }

    companion object {
        /** 全周掩码：bit0..bit6 全为 1。 */
        const val WEEKLY_DAYS_ALL: Int = 0x7F

        /** 周一（`LocalDate.dayOfWeek.value` 口径）。 */
        private const val FIRST_WEEKDAY: Int = 1

        /** 周日。 */
        private const val LAST_WEEKDAY: Int = 7
    }
}
