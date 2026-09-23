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
    val emoji: String = DEFAULT_EMOJI,
    val colorHex: String = DEFAULT_COLOR_HEX,
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
     *   掩码为空时同样返回 `null` —— 理论不可达：表单在"每周指定日 + 一个都没选"时禁用保存
     *   并提示「至少选一天」（旧版是悄悄兜成全周，台账 A13 已改）。
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
        /**
         * 新建习惯的默认图标。
         *
         * ⚠️ **不能是 ✅**（`\u2705`）：习惯行把 emoji 画在行首、真正的完成勾选框在行尾，
         * 于是"没打卡的习惯"看着像"已经打卡了"（真机走查 #9）。默认值换成不带判定的 💪，
         * 用户自己选 ✅ 仍然允许 —— 那是他的选择，不是我们替他预设状态。
         */
        const val DEFAULT_EMOJI: String = "\uD83D\uDCAA"

        /**
         * 新建习惯的默认主题色。**全工程只有这一个 `#2196F3` 字面量**：
         * 色板（`ui.theme.HABIT_COLOR_HEXES` 首项）、`habits.color_hex` 列默认值、
         * 备份负载默认值都指到这里。色板本身在主题文件里（架构 §7.5），但它存的是**数据**，
         * 会写库、会随备份往返，所以默认值归域层。
         */
        const val DEFAULT_COLOR_HEX: String = "#2196F3"

        /** 全周掩码：bit0..bit6 全为 1。 */
        const val WEEKLY_DAYS_ALL: Int = 0x7F

        /** 周一（`LocalDate.dayOfWeek.value` 口径）。 */
        private const val FIRST_WEEKDAY: Int = 1

        /** 周日。 */
        private const val LAST_WEEKDAY: Int = 7
    }
}
