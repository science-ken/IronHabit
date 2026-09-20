package com.ironhabit.app.domain.model

/**
 * 动作分类。
 *
 * - [BODYWEIGHT] 自重
 * - [STRENGTH] 力量
 * - [CARDIO] 有氧
 * - [CUSTOM] 自建
 */
enum class ExerciseCategory {
    BODYWEIGHT,
    STRENGTH,
    CARDIO,
    CUSTOM,
}

/**
 * 动作来源（三态）。
 *
 * 取代 v1 的布尔 `isBuiltIn`：单一枚举天然只有 3 个合法值，避免
 * `isBuiltIn + isAiSuggested` 两个布尔组合出非法状态（boolean-pair 反模式）。
 *
 * 产品规则：**任何用户编辑（改名 / 改备注 / 改默认组次 / 改肌群）→ [CUSTOM]**，单向不可逆。
 */
enum class ExerciseSource {
    /** 内置动作库（首启播种，≥ 40 个）。 */
    BUILT_IN,

    /** 用户自建，**或**任何被用户编辑过的动作。 */
    CUSTOM,

    /** AI 推荐、用户尚未改过。 */
    AI_SUGGESTED,
}

/**
 * 领域模型：训练动作（内置 / 自建 / AI 推荐）。
 *
 * 该模型为纯 Kotlin，不依赖任何 Android 类型，便于 JVM 单测。
 * 字段与 `exercises` 表（见架构 §3.1 / §3.8）一一对应。
 *
 * @property id 主键，自增；`0` 表示尚未落库
 * @property name 动作名（唯一）
 * @property category 分类
 * @property source 三态来源（唯一真源，取代 v1 的 `isBuiltIn`）
 * @property muscleGroups **有序**肌群列表：**第一个 = 主肌群**，其后为辅。
 *   由 `exercises.muscle_group`（单数列名，内容为有序 CSV）解析而来。标签取值见
 *   [MuscleGroup]（唯一真源）。
 * @property equipment 本动作**需要**的器械，v7 新增。空列表 = **未标注**（存量行 / 自建动作），
 *   与"不需要器械"（`listOf(Equipment.NONE)`）是**两回事**，不可合并 —— 前者要回落旧判据。
 * @property note 动作要点备注，可空
 * @property isActive 是否启用（`false` = 停用）
 * @property defaultSets 默认组数
 * @property defaultReps 默认每组次数
 * @property defaultDurationSec 默认时长（秒），有氧动作使用
 * @property sortOrder 展示排序
 * @property timesUsed 被打卡次数（排序用）
 * @property createdAt 创建时间戳（UTC 毫秒）
 */
data class Exercise(
    val id: Long = 0L,
    val name: String = "",
    val category: ExerciseCategory = ExerciseCategory.BODYWEIGHT,
    val source: ExerciseSource = ExerciseSource.BUILT_IN,
    val muscleGroups: List<String> = emptyList(),
    val equipment: List<Equipment> = emptyList(),
    val note: String? = null,
    val isActive: Boolean = true,
    val defaultSets: Int? = 3,
    val defaultReps: Int? = 12,
    val defaultDurationSec: Int? = null,
    val sortOrder: Int = 0,
    val timesUsed: Int = 0,
    val createdAt: Long = 0L,
) {
    /** 便捷派生：是否内置动作（等价于 `source == BUILT_IN`）。只读，不可写。 */
    val isBuiltIn: Boolean get() = source == ExerciseSource.BUILT_IN

    /** 主肌群（有序列表首个），无则 `null`。 */
    val primaryMuscleGroup: String? get() = muscleGroups.firstOrNull()
}
