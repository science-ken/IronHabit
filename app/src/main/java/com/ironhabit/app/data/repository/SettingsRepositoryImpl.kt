package com.ironhabit.app.data.repository

import com.ironhabit.app.data.preferences.SettingsDataStore
import com.ironhabit.app.domain.model.AppSettings
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * [SettingsRepository] 的 data 层实现（包装 [SettingsDataStore]）。
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: SettingsDataStore,
) : SettingsRepository {

    override fun settings(): Flow<AppSettings> = dataStore.settings

    override suspend fun setTheme(mode: ThemeMode) {
        dataStore.setTheme(mode)
    }

    override suspend fun setUnit(system: UnitSystem) {
        dataStore.setUnit(system)
    }

    override suspend fun setReminderEnabled(enabled: Boolean) {
        dataStore.setReminderEnabled(enabled)
    }

    override suspend fun setReminderTime(hour: Int, minute: Int) {
        dataStore.setReminderTime(hour, minute)
    }

    override suspend fun markFirstLaunchCompleted() {
        dataStore.markFirstLaunchCompleted()
    }
}
