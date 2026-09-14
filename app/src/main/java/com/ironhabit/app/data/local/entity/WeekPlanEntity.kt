package com.ironhabit.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `week_plans` 表：周一~周日计划条目。
 *
 * 外键：`exercise_id` → `exercises.id`（`CASCADE`）。
 * 索引：`day_of_week`、`exercise_id`、`(day_of_week, exercise_id)` 唯一（同日同动作不重复）。
 */
@Entity(
    tableName = "week_plans",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["day_of_week"]),
        Index(value = ["exercise_id"]),
        Index(value = ["day_of_week", "exercise_id"], unique = true),
    ],
)
data class WeekPlanEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,

    @ColumnInfo(name = "day_of_week")
    val dayOfWeek: Int,

    @ColumnInfo(name = "target_sets")
    val targetSets: Int = 3,

    @ColumnInfo(name = "target_reps")
    val targetReps: Int = 12,

    @ColumnInfo(name = "target_weight_kg")
    val targetWeightKg: Float? = null,

    @ColumnInfo(name = "target_duration_min")
    val targetDurationMin: Int? = null,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)
