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
 * 领域模型：训练动作（内置 / 自建）。
 *
 * 该模型为纯 Kotlin，不依赖任何 Android 类型，便于 JVM 单测。
 * 字段与 `exercises` 表（见架构 §3.1）一一对应。
 *
 * @property id 主键，自增；`0` 表示尚未落库
 * @property name 动作名（唯一）
 * @property category 分类
 * @property muscleGroup 肌群标签，可空
 * @property isBuiltIn 是否内置动作
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
    val muscleGroup: String? = null,
    val isBuiltIn: Boolean = false,
    val isActive: Boolean = true,
    val defaultSets: Int? = 3,
    val defaultReps: Int? = 12,
    val defaultDurationSec: Int? = null,
    val sortOrder: Int = 0,
    val timesUsed: Int = 0,
    val createdAt: Long = 0L,
)
