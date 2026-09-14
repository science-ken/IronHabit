package com.ironhabit.app.domain.model

import kotlinx.serialization.Serializable

/**
 * 导出 / 导入根模型（JSON Schema 见架构 §3.4）。
 *
 * 使用 kotlinx.serialization 做纯本地文件 JSON 序列化，**不涉及任何网络**。
 * 导入策略为「整体替换」：清空 6 张表后按 JSON 重建，`id` 保留原值以便外键自洽。
 *
 * @property schemaVersion 结构版本号
 * @property exportedAt 导出时刻（UTC 毫秒）
 * @property appVersion 应用版本
 * @property exercises 动作列表
 * @property weekPlans 周计划列表
 * @property checkIns 打卡列表
 * @property habits 习惯列表
 * @property habitLogs 习惯日志列表
 * @property bodyMetrics 身体数据列表
 * @property settings 设置快照
 */
@Serializable
data class BackupPayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAt: Long = 0L,
    val appVersion: String = "1.0",
    val exercises: List<ExerciseBackup> = emptyList(),
    val weekPlans: List<WeekPlanBackup> = emptyList(),
    val checkIns: List<CheckInBackup> = emptyList(),
    val habits: List<HabitBackup> = emptyList(),
    val habitLogs: List<HabitLogBackup> = emptyList(),
    val bodyMetrics: List<BodyMetricBackup> = emptyList(),
    val settings: SettingsBackup = SettingsBackup(),
) {
    companion object {
        /** 当前结构版本。v2：逐组 mask / RPE / 三态来源 / 多肌群 / 计划用户改动标记 / 习惯目标值。 */
        const val CURRENT_SCHEMA_VERSION: Int = 2
    }
}

/** 动作备份行。 */
@Serializable
data class ExerciseBackup(
    val id: Long = 0L,
    val name: String = "",
    val category: String = ExerciseCategory.BODYWEIGHT.name,
    /** **有序 CSV**（v2 起语义）：第一个 = 主肌群。旧备份的单值天然合法。 */
    val muscleGroup: String? = null,
    /**
     * 三态来源（v2 新增）。`null` = 老备份（按 [isBuiltIn] 推导导入时的来源）。
     */
    val source: String? = null,
    /** 动作要点备注（v2 新增）。 */
    val note: String? = null,
    @Deprecated("v2 起由 source 取代，仅为兼容旧备份保留")
    val isBuiltIn: Boolean = false,
    val isActive: Boolean = true,
    val defaultSets: Int? = null,
    val defaultReps: Int? = null,
    val defaultDurationSec: Int? = null,
    val sortOrder: Int = 0,
    val timesUsed: Int = 0,
)

/** 周计划备份行。 */
@Serializable
data class WeekPlanBackup(
    val id: Long = 0L,
    val exerciseId: Long = 0L,
    val dayOfWeek: Int = 1,
    val targetSets: Int = 3,
    val targetReps: Int = 12,
    val targetWeightKg: Float? = null,
    val targetDurationMin: Int? = null,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    /** 行级：本条（某天 × 某动作）被用户手动改过（v2 新增）。 */
    val isUserEdited: Boolean = false,
)

/** 打卡备份行。 */
@Serializable
data class CheckInBackup(
    val id: Long = 0L,
    val exerciseId: Long = 0L,
    val planId: Long? = null,
    val dateEpochDay: Long = 0L,
    val dateStartMillis: Long = 0L,
    /** 派生冗余列：恒等于 [completedSetsMask] 置位数（旧的 v1 备份仅有此列）。 */
    val completedSets: Int = 0,
    /**
     * 逐组完成 bitmask（v2 新增，唯一真源）。
     * `null` = 老备份（导入时由 [completedSets] 折算为低 n 位全 1）。
     */
    val completedSetsMask: Int? = null,
    /** 主观强度 RPE `1..10`（v2 新增，可空）。 */
    val rpe: Int? = null,
    val completedReps: Int = 0,
    val weightKg: Float? = null,
    val durationMinutes: Int? = null,
    val notes: String? = null,
    val isQuick: Boolean = false,
    val loggedAtMillis: Long = 0L,
)

/** 习惯备份行。 */
@Serializable
data class HabitBackup(
    val id: Long = 0L,
    val name: String = "",
    val emoji: String = "",
    val colorHex: String = "#2196F3",
    val frequency: String = HabitFrequency.DAILY.name,
    val weeklyDaysMask: Int = Habit.WEEKLY_DAYS_ALL,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int? = null,
    val reminderMinute: Int? = null,
    /** 备注（v2 新增）。 */
    val note: String? = null,
    /** 目标数值（v2 新增）。`null` = 纯勾选型习惯；非 `null` = 计量型。 */
    val targetValue: Double? = null,
    /** 目标单位文案（v2 新增）。 */
    val targetUnit: String? = null,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
)

/** 习惯日志备份行。 */
@Serializable
data class HabitLogBackup(
    val id: Long = 0L,
    val habitId: Long = 0L,
    val dateEpochDay: Long = 0L,
    val dateStartMillis: Long = 0L,
    val isCompleted: Boolean = true,
    val note: String? = null,
    val loggedAtMillis: Long = 0L,
)

/** 身体数据备份行。 */
@Serializable
data class BodyMetricBackup(
    val id: Long = 0L,
    val type: String = BodyMetricType.WEIGHT.name,
    val value: Float = 0f,
    val unit: String = "kg",
    val dateEpochDay: Long = 0L,
    val dateStartMillis: Long = 0L,
    val note: String? = null,
)

/** 设置快照。 */
@Serializable
data class SettingsBackup(
    val themeMode: String = ThemeMode.SYSTEM.name,
    val unitSystem: String = UnitSystem.METRIC.name,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
)
