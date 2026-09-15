package com.ironhabit.app.domain.repository

import com.ironhabit.app.domain.model.AppSettings
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import com.ironhabit.app.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow

/**
 * 设置仓库接口（内部包装 DataStore）。
 */
interface SettingsRepository {

    /** 观察设置变化。 */
    fun settings(): Flow<AppSettings>

    // ---------------- 用户档案（我的档案，单人单份）----------------

    /** 观察用户档案变化（不落 Room，存 DataStore）。 */
    fun profile(): Flow<UserProfile>

    /** 是否启用「AI 联网生成」（默认 false = 纯本地规则；Key 配置见 AiCredentialsStore）。 */
    fun aiRemoteEnabled(): Flow<Boolean>

    /** 开关「AI 联网生成」。 */
    suspend fun setAiRemoteEnabled(enabled: Boolean)

    /** 写入性别；`null` = 清空。 */
    suspend fun setProfileGender(gender: Gender?)

    /** 写入年龄；`null` = 清空（越界由实现钳制）。 */
    suspend fun setProfileAge(age: Int?)

    /** 写入身高；`null` = 清空（越界由实现钳制）。 */
    suspend fun setProfileHeightCm(heightCm: Int?)

    /** 写入体脂率；`null` = 清空（越界由实现钳制）。 */
    suspend fun setProfileBodyFatPct(bodyFatPct: Float?)

    /** 写入健身目标。 */
    suspend fun setProfileGoal(goal: Goal)

    /** 写入目标体重；`null` = 清空（越界由实现钳制）。 */
    suspend fun setProfileGoalWeightKg(goalWeightKg: Float?)

    /** 写入可用器械集合。 */
    suspend fun setProfileEquipment(equipment: Set<Equipment>)

    /** 写入伤病部位集合。 */
    suspend fun setProfileInjuryAreas(areas: Set<InjuryArea>)

    /** 写入伤病备注；空白/`null` = 清空。 */
    suspend fun setProfileInjuryNote(note: String?)

    /** 写入饮食忌口集合。 */
    suspend fun setProfileDietaryAvoid(avoid: Set<DietRestriction>)

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
