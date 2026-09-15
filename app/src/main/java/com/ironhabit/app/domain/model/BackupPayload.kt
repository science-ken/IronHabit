package com.ironhabit.app.domain.model

import kotlinx.serialization.Serializable

/**
 * 导出 / 导入根模型（JSON Schema 见架构 §3.4）。
 *
 * 使用 kotlinx.serialization 做纯本地文件 JSON 序列化，**不涉及任何网络**。
 * 导入策略为「整体替换」：清空 6 张表后按 JSON 重建，`id` 保留原值以便外键自洽。
 *
 * **向后兼容契约（v3）**：新增字段一律带默认值，因此「旧备份缺字段 → 解码即默认值」不会抛异常。
 * - 6 张表的备份行自 v3 起携带 `createdAt`；v1/v2 老备份无该字段（解码为 `0`），
 *   导入时由 data 层回落到**导入时刻**，避免 `created_at = 0` 破坏 `ORDER BY created_at DESC` 的历史排序。
 * - `settings` 自 v3 起携带用户档案快照；可空字段 `null` = 「未携带 / 用户未填」，
 *   导入时**跳过而不覆盖**本地已有值（详见 [SettingsBackup]）。
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
        /**
         * 当前结构版本。
         *
         * - v2：逐组 mask / RPE / 三态来源 / 多肌群 / 计划用户改动标记 / 习惯目标值。
         * - v3：备份携带**用户档案快照**（`settings` 内新增 11 项）+ 6 张表全部携带 `createdAt`。
         */
        const val CURRENT_SCHEMA_VERSION: Int = 3
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
    /** 创建时刻（UTC 毫秒，v3 新增）；`0` = 老备份未携带 → 导入时回落到导入时刻。 */
    val createdAt: Long = 0L,
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
    /** 创建时刻（UTC 毫秒，v3 新增）；`0` = 老备份未携带 → 导入时回落到导入时刻。 */
    val createdAt: Long = 0L,
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
    /** 创建时刻（UTC 毫秒，v3 新增）；`0` = 老备份未携带 → 导入时回落到导入时刻。 */
    val createdAt: Long = 0L,
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
    /** 创建时刻（UTC 毫秒，v3 新增）；`0` = 老备份未携带 → 导入时回落到导入时刻。 */
    val createdAt: Long = 0L,
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
    /** 创建时刻（UTC 毫秒，v3 新增）；`0` = 老备份未携带 → 导入时回落到导入时刻。 */
    val createdAt: Long = 0L,
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
    /** 创建时刻（UTC 毫秒，v3 新增）；`0` = 老备份未携带 → 导入时回落到导入时刻。 */
    val createdAt: Long = 0L,
)

/**
 * 设置快照（v3 起含**用户档案** + AI 联网开关）。
 *
 * 档案同处 `SettingsDataStore`（不落 Room、不需 schema 迁移），但 AI 教练与本地规则引擎都要消费它，
 * 旧版备份不带这些字段 → 恢复后档案被静默抹掉（v3 修复）。
 *
 * **字段携带契约**：全部新增字段都有默认值，老 v2 备份直接解码、不抛异常。
 * 可空字段（`gender` / `age` / `heightCm` / `bodyFatPct` / `goal` / `goalWeightKg` / `injuryNote`）
 * 用 `null` 表示「备份未携带或用户未填」；三个集合字段与 [aiRemoteEnabled] 在 v3 备份里
 * 一定被显式编码（导出 `encodeDefaults = true`），因此「v3 的空集 / false」是**明确快照**
 * 而非「缺失」；data 层据此决定是否覆盖本地值（见 `BackupRepositoryImpl`）。
 *
 * 枚举一律存 `name`（不存 ordinal，新增/重排枚举值不会错位）。
 *
 * @property themeMode 主题模式（[ThemeMode.name]）
 * @property unitSystem 单位制（[UnitSystem.name]）
 * @property reminderEnabled 是否开启每日提醒
 * @property reminderHour 提醒小时（`0..23`）
 * @property reminderMinute 提醒分钟（`0..59`）
 * @property gender 生理性别（[Gender.name]，v3 新增）
 * @property age 年龄（岁，v3 新增）
 * @property heightCm 身高（cm，v3 新增）
 * @property bodyFatPct 体脂率（%，v3 新增）
 * @property goal 健身目标（[Goal.name]，v3 新增）
 * @property goalWeightKg 目标体重（kg，v3 新增）
 * @property equipment 可用器械（[Equipment.name] 集合，v3 新增）
 * @property injuryAreas 伤病部位（[InjuryArea.name] 集合，v3 新增）
 * @property injuryNote 伤病备注（自由文本，v3 新增）
 * @property dietaryAvoid 饮食忌口（[DietRestriction.name] 集合，v3 新增）
 * @property aiRemoteEnabled 是否启用「AI 联网生成」（v3 新增；默认 `false` = 纯本地规则）
 */
@Serializable
data class SettingsBackup(
    val themeMode: String = ThemeMode.SYSTEM.name,
    val unitSystem: String = UnitSystem.METRIC.name,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val gender: String? = null,
    val age: Int? = null,
    val heightCm: Int? = null,
    val bodyFatPct: Float? = null,
    val goal: String? = null,
    val goalWeightKg: Float? = null,
    val equipment: Set<String> = emptySet(),
    val injuryAreas: Set<String> = emptySet(),
    val injuryNote: String? = null,
    val dietaryAvoid: Set<String> = emptySet(),
    val aiRemoteEnabled: Boolean = false,
)
