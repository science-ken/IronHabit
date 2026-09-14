package com.ironhabit.app.domain.model

/**
 * 主题模式。
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}

/**
 * 单位制。
 */
enum class UnitSystem {
    METRIC,
    IMPERIAL,
}

/**
 * 设置聚合模型（主题 / 单位 / 提醒）。
 *
 * @property themeMode 主题模式
 * @property unitSystem 单位制
 * @property reminderEnabled 是否开启每日提醒
 * @property reminderHour 提醒小时（`0..23`）
 * @property reminderMinute 提醒分钟（`0..59`）
 * @property isFirstLaunch 是否首次启动（用于首启幂等播种判定）
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val isFirstLaunch: Boolean = true,
)
