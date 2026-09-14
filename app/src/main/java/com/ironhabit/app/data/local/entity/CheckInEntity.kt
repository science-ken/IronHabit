package com.ironhabit.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `check_ins` 表：训练打卡记录。
 *
 * - 外键：`exercise_id` → `exercises.id`（`CASCADE`）。
 * - `plan_id` **故意不建外键** —— 删除计划条目不得抹掉历史打卡记录。
 * - 唯一约束：`(exercise_id, date_epoch_day)` —— 保证「一键打卡」幂等。
 */
@Entity(
    tableName = "check_ins",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["exercise_id"]),
        Index(value = ["date_epoch_day"]),
        Index(value = ["date_start_millis"]),
        Index(value = ["exercise_id", "date_epoch_day"], unique = true),
    ],
)
data class CheckInEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,

    /** 来源计划条目 id，可空；**不建外键**。 */
    @ColumnInfo(name = "plan_id")
    val planId: Long? = null,

    /** 日期唯一口径：`LocalDate.toEpochDays()`。 */
    @ColumnInfo(name = "date_epoch_day")
    val dateEpochDay: Long,

    @ColumnInfo(name = "date_start_millis")
    val dateStartMillis: Long,

    @ColumnInfo(name = "completed_sets")
    val completedSets: Int = 0,

    @ColumnInfo(name = "completed_reps")
    val completedReps: Int = 0,

    @ColumnInfo(name = "weight_kg")
    val weightKg: Float? = null,

    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int? = null,

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    @ColumnInfo(name = "is_quick")
    val isQuick: Boolean = false,

    @ColumnInfo(name = "logged_at_millis")
    val loggedAtMillis: Long = 0L,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)
