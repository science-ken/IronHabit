package com.ironhabit.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ironhabit.app.domain.model.ExerciseCategory

/**
 * `exercises` 表：训练动作（内置 / 自建）。
 *
 * 索引：`name` 唯一（播种幂等）、`category`、`is_active`（停用过滤）。
 */
@Entity(
    tableName = "exercises",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["category"]),
        Index(value = ["is_active"]),
    ],
)
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "category")
    val category: ExerciseCategory,

    @ColumnInfo(name = "muscle_group")
    val muscleGroup: String? = null,

    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean = false,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "default_sets")
    val defaultSets: Int = 3,

    @ColumnInfo(name = "default_reps")
    val defaultReps: Int = 12,

    @ColumnInfo(name = "default_duration_sec")
    val defaultDurationSec: Int = 0,

    @ColumnInfo(name = "times_used")
    val timesUsed: Int = 0,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)
