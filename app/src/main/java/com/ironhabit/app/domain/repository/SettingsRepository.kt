package com.ironhabit.app.domain.repository

import com.ironhabit.app.domain.model.AppSettings
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import kotlinx.coroutines.flow.Flow

/**
 * 设置仓库接口（内部包装 DataStore）。
 */
interface SettingsRepository {

    /** 观察设置变化。 */
    fun settings(): Flow<AppSettings>

    /** 设置主题模式。 */
    suspend fun setTheme(mode: ThemeMode)

    /** 设置单位制。 */
    suspend fun setUnit(system: UnitSystem)

    /** 开关每日提醒。 */
    suspend fun setReminderEnabled(enabled: Boolean)

    /** 设置每日提醒时间。 */
    suspend fun setReminderTime(hour: Int, minute: Int)

    /** 标记首次启动已完成（用于首启播种判定）。 */
    suspend fun markFirstLaunchCompleted()
}
