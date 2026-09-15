package com.ironhabit.app.data.repository

import com.ironhabit.app.data.preferences.SettingsDataStore
import com.ironhabit.app.domain.model.AppSettings
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import com.ironhabit.app.domain.model.UserProfile
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

    override fun profile(): Flow<UserProfile> = dataStore.profile

    override fun aiRemoteEnabled(): Flow<Boolean> = dataStore.aiRemoteEnabled

    override suspend fun setAiRemoteEnabled(enabled: Boolean) {
        dataStore.setAiRemoteEnabled(enabled)
    }

    override suspend fun setProfileGender(gender: Gender?) {
        dataStore.setProfileGender(gender)
    }

    override suspend fun setProfileAge(age: Int?) {
        dataStore.setProfileAge(age)
    }

    override suspend fun setProfileHeightCm(heightCm: Int?) {
        dataStore.setProfileHeightCm(heightCm)
    }

    override suspend fun setProfileBodyFatPct(bodyFatPct: Float?) {
        dataStore.setProfileBodyFatPct(bodyFatPct)
    }

    override suspend fun setProfileGoal(goal: Goal) {
        dataStore.setProfileGoal(goal)
    }

    override suspend fun setProfileGoalWeightKg(goalWeightKg: Float?) {
        dataStore.setProfileGoalWeightKg(goalWeightKg)
    }

    override suspend fun setProfileEquipment(equipment: Set<Equipment>) {
        dataStore.setProfileEquipment(equipment)
    }

    override suspend fun setProfileInjuryAreas(areas: Set<InjuryArea>) {
        dataStore.setProfileInjuryAreas(areas)
    }

    override suspend fun setProfileInjuryNote(note: String?) {
        dataStore.setProfileInjuryNote(note)
    }

    override suspend fun setProfileDietaryAvoid(avoid: Set<DietRestriction>) {
        dataStore.setProfileDietaryAvoid(avoid)
    }

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
