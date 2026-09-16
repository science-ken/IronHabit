package com.ironhabit.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ironhabit.app.domain.model.AppSettings
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.ProfileLimits
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.decodeEnum
import com.ironhabit.app.domain.model.decodeEnumSet
import com.ironhabit.app.domain.model.encodeEnumSet
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore 单例扩展（按文件声明，保证只有一个 DataStore 实例）。 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsDataStore.STORE_NAME,
)

/**
 * 设置持久化（DataStore Preferences）。
 *
 * 键：主题 / 单位 / 提醒开关 / 提醒时间 / 首启标记，以及用户档案 `profile_*`（11 键）。
 *
 * **用户档案**（单人单份）与主题/单位同处一个 DataStore 文件，**不落 Room、不需 schema 迁移**。
 * 多选集合（器械 / 伤病部位 / 忌口）用 [`stringSetPreferencesKey`] 存枚举 `name`（**不存 ordinal**）。
 * 所有数值写入即 `coerceIn`（见 [ProfileLimits]），越界只钳制、不抛异常。
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

    /**
     * 观察用户档案变化（读取失败时回落到空档案，**不崩**）。
     *
     * 未知枚举 `name` 读入时忽略并回落默认（不抛异常）；不设「是否已配置」布尔键，
     * 「是否填全」由字段是否为 `null` / 集合是否为空**直接推导**。
     */
    val profile: Flow<UserProfile> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            UserProfile(
                gender = decodeEnum<Gender>(preferences[Keys.PROFILE_GENDER]),
                age = preferences[Keys.PROFILE_AGE],
                heightCm = preferences[Keys.PROFILE_HEIGHT_CM],
                bodyFatPct = preferences[Keys.PROFILE_BODY_FAT_PCT],
                goal = decodeEnum<Goal>(preferences[Keys.PROFILE_GOAL]) ?: Goal.MAINTAIN,
                goalWeightKg = preferences[Keys.PROFILE_GOAL_WEIGHT_KG],
                equipment = decodeEnumSet(preferences[Keys.PROFILE_EQUIPMENT].orEmpty()),
                injuryAreas = decodeEnumSet(preferences[Keys.PROFILE_INJURY_AREA].orEmpty()),
                injuryNote = preferences[Keys.PROFILE_INJURY_NOTE],
                dietaryAvoid = decodeEnumSet(preferences[Keys.PROFILE_DIET_AVOID].orEmpty()),
                // P1：每周训练天数。缺键 = 从未设置过 → 用默认 3 天（与旧版钉死 3 天一致）；
                // 已写入的值越界（理论上不该发生）也在读侧再钳一次，绝不把荒谬天数喂给规则引擎。
                trainingDaysPerWeek = ProfileLimits.coerceTrainingDaysPerWeek(
                    preferences[Keys.PROFILE_TRAINING_DAYS_PER_WEEK]
                        ?: ProfileLimits.DEFAULT_TRAINING_DAYS_PER_WEEK,
                ),
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

    // ---------------- 用户档案（profile_*）写入：一律 coerceIn ----------------

    /** 写入性别；`null` = 清空（未填）。 */
    suspend fun setProfileGender(gender: Gender?) {
        context.settingsDataStore.edit { preferences ->
            if (gender == null) {
                preferences.remove(Keys.PROFILE_GENDER)
            } else {
                preferences[Keys.PROFILE_GENDER] = gender.name
            }
        }
    }

    /** 写入年龄；`null` = 清空。越界钳制到 `14–100`。 */
    suspend fun setProfileAge(age: Int?) {
        context.settingsDataStore.edit { preferences ->
            if (age == null) {
                preferences.remove(Keys.PROFILE_AGE)
            } else {
                preferences[Keys.PROFILE_AGE] = ProfileLimits.coerceAge(age)
            }
        }
    }

    /** 写入身高；`null` = 清空。越界钳制到 `140–220`。 */
    suspend fun setProfileHeightCm(heightCm: Int?) {
        context.settingsDataStore.edit { preferences ->
            if (heightCm == null) {
                preferences.remove(Keys.PROFILE_HEIGHT_CM)
            } else {
                preferences[Keys.PROFILE_HEIGHT_CM] = ProfileLimits.coerceHeightCm(heightCm)
            }
        }
    }

    /** 写入体脂率；`null` = 清空。越界钳制到 `3–60`。 */
    suspend fun setProfileBodyFatPct(bodyFatPct: Float?) {
        context.settingsDataStore.edit { preferences ->
            if (bodyFatPct == null) {
                preferences.remove(Keys.PROFILE_BODY_FAT_PCT)
            } else {
                preferences[Keys.PROFILE_BODY_FAT_PCT] = ProfileLimits.coerceBodyFatPct(bodyFatPct)
            }
        }
    }

    /** 写入目标（默认 [Goal.MAINTAIN]）。 */
    suspend fun setProfileGoal(goal: Goal) {
        context.settingsDataStore.edit { it[Keys.PROFILE_GOAL] = goal.name }
    }

    /** 写入目标体重；`null` = 清空。越界钳制到 `30–300`。 */
    suspend fun setProfileGoalWeightKg(goalWeightKg: Float?) {
        context.settingsDataStore.edit { preferences ->
            if (goalWeightKg == null) {
                preferences.remove(Keys.PROFILE_GOAL_WEIGHT_KG)
            } else {
                preferences[Keys.PROFILE_GOAL_WEIGHT_KG] =
                    ProfileLimits.coerceGoalWeightKg(goalWeightKg)
            }
        }
    }

    /** 写入可用器械集合（存 `name`）。 */
    suspend fun setProfileEquipment(equipment: Set<Equipment>) {
        context.settingsDataStore.edit { it[Keys.PROFILE_EQUIPMENT] = encodeEnumSet(equipment) }
    }

    /** 写入伤病部位集合（存 `name`）。 */
    suspend fun setProfileInjuryAreas(areas: Set<InjuryArea>) {
        context.settingsDataStore.edit { it[Keys.PROFILE_INJURY_AREA] = encodeEnumSet(areas) }
    }

    /** 写入伤病备注；空白/`null` = 清空。截断到 [ProfileLimits.INJURY_NOTE_MAX_LENGTH] 字。 */
    suspend fun setProfileInjuryNote(note: String?) {
        context.settingsDataStore.edit { preferences ->
            val normalized = note?.trim()
            if (normalized.isNullOrEmpty()) {
                preferences.remove(Keys.PROFILE_INJURY_NOTE)
            } else {
                preferences[Keys.PROFILE_INJURY_NOTE] = ProfileLimits.coerceInjuryNote(normalized)
            }
        }
    }

    /** 写入饮食忌口集合（存 `name`）。 */
    suspend fun setProfileDietaryAvoid(avoid: Set<DietRestriction>) {
        context.settingsDataStore.edit { it[Keys.PROFILE_DIET_AVOID] = encodeEnumSet(avoid) }
    }

    /** 写入每周训练天数；越界钳制到 `3–6`。 */
    suspend fun setProfileTrainingDaysPerWeek(days: Int) {
        context.settingsDataStore.edit {
            it[Keys.PROFILE_TRAINING_DAYS_PER_WEEK] = ProfileLimits.coerceTrainingDaysPerWeek(days)
        }
    }

    /**
     * 是否启用「AI 联网生成」（联网一期，默认 **false** = 纯本地规则）。
     *
     * 只控制 `GenerateTrainingPlanUseCase` / `SuggestExercisesUseCase` 走远端还是本地；
     * **API Key 不在这里**（见 [AiCredentialsStore]，加密存储、不进 DataStore）。
     */
    val aiRemoteEnabled: Flow<Boolean> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences -> preferences[Keys.AI_REMOTE_ENABLED] ?: false }

    /** 开关「AI 联网生成」（默认关；打开仍需用户已配置 API Key 才会真正走远端）。 */
    suspend fun setAiRemoteEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AI_REMOTE_ENABLED] = enabled }
    }

    /**
     * 是否仍需向用户申请通知权限（API 33+）。
     *
     * 只要弹过一次系统授权框就不再主动弹，避免反复打扰；
     * 用户后续可在设置页的引导卡里手动再次申请。
     */
    suspend fun shouldAskNotificationPermission(): Boolean =
        context.settingsDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { preferences -> preferences[Keys.NOTIFICATION_PERM_ASKED] != true }
            .first()

    /** 标记「已申请过通知权限」。 */
    suspend fun markNotificationPermissionAsked() {
        context.settingsDataStore.edit { it[Keys.NOTIFICATION_PERM_ASKED] = true }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val REMINDER_HOUR = intPreferencesKey("reminder_hour")
        val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
        val FIRST_LAUNCH = booleanPreferencesKey("first_launch")
        val NOTIFICATION_PERM_ASKED = booleanPreferencesKey("notification_permission_asked")

        // ---- 用户档案（profile_*）：单值用 String/Int/Float，多选用 StringSet（存枚举 name）----
        val PROFILE_GENDER = stringPreferencesKey("profile_gender")
        val PROFILE_AGE = intPreferencesKey("profile_age")
        val PROFILE_HEIGHT_CM = intPreferencesKey("profile_height_cm")
        val PROFILE_BODY_FAT_PCT = floatPreferencesKey("profile_body_fat_pct")
        val PROFILE_GOAL = stringPreferencesKey("profile_goal")
        val PROFILE_GOAL_WEIGHT_KG = floatPreferencesKey("profile_goal_weight_kg")
        val PROFILE_EQUIPMENT = stringSetPreferencesKey("profile_equipment")
        val PROFILE_INJURY_AREA = stringSetPreferencesKey("profile_injury_area")
        val PROFILE_INJURY_NOTE = stringPreferencesKey("profile_injury_note")
        val PROFILE_DIET_AVOID = stringSetPreferencesKey("profile_diet_avoid")
        val PROFILE_TRAINING_DAYS_PER_WEEK = intPreferencesKey("profile_training_days_per_week")

        // ---- AI 联网（联网一期）：仅开关，Key 走 AiCredentialsStore（加密、不进 DataStore）----
        val AI_REMOTE_ENABLED = booleanPreferencesKey("ai_remote_enabled")
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
