package com.ironhabit.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ironhabit.app.domain.model.AppSettings
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** DataStore 单例扩展（按文件声明，保证只有一个 DataStore 实例）。 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsDataStore.STORE_NAME,
)

/**
 * 设置持久化（DataStore Preferences）。
 *
 * 键：主题 / 单位 / 提醒开关 / 提醒时间 / 首启标记。
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 观察设置变化（读取失败时回落到默认值）。 */
    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            AppSettings(
                themeMode = preferences[Keys.THEME_MODE]
                    ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                    ?: ThemeMode.SYSTEM,
                unitSystem = preferences[Keys.UNIT_SYSTEM]
                    ?.let { stored -> UnitSystem.entries.firstOrNull { it.name == stored } }
                    ?: UnitSystem.METRIC,
                reminderEnabled = preferences[Keys.REMINDER_ENABLED] ?: true,
                reminderHour = preferences[Keys.REMINDER_HOUR] ?: DEFAULT_REMINDER_HOUR,
                reminderMinute = preferences[Keys.REMINDER_MINUTE] ?: DEFAULT_REMINDER_MINUTE,
                isFirstLaunch = preferences[Keys.FIRST_LAUNCH] ?: true,
            )
        }

    /** 设置主题模式。 */
    suspend fun setTheme(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    /** 设置单位制。 */
    suspend fun setUnit(system: UnitSystem) {
        context.settingsDataStore.edit { it[Keys.UNIT_SYSTEM] = system.name }
    }

    /** 开关每日提醒。 */
    suspend fun setReminderEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.REMINDER_ENABLED] = enabled }
    }

    /** 设置每日提醒时间。 */
    suspend fun setReminderTime(hour: Int, minute: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.REMINDER_HOUR] = hour.coerceIn(MIN_HOUR, MAX_HOUR)
            preferences[Keys.REMINDER_MINUTE] = minute.coerceIn(MIN_MINUTE, MAX_MINUTE)
        }
    }

    /** 标记首次启动已完成。 */
    suspend fun markFirstLaunchCompleted() {
        context.settingsDataStore.edit { it[Keys.FIRST_LAUNCH] = false }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val REMINDER_HOUR = intPreferencesKey("reminder_hour")
        val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
        val FIRST_LAUNCH = booleanPreferencesKey("first_launch")
    }

    companion object {
        /** DataStore 文件名。 */
        const val STORE_NAME: String = "ironhabit_settings"

        private const val DEFAULT_REMINDER_HOUR: Int = 20
        private const val DEFAULT_REMINDER_MINUTE: Int = 0

        private const val MIN_HOUR: Int = 0
        private const val MAX_HOUR: Int = 23
        private const val MIN_MINUTE: Int = 0
        private const val MAX_MINUTE: Int = 59
    }
}
