package com.ironhabit.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `habit_logs` 表：习惯逐日勾选记录。
 *
 * - 外键：`habit_id` → `habits.id`（`CASCADE`）。
 * - 唯一约束：`(habit_id, date_epoch_day)` —— 保证勾选幂等。
 */
@Entity(
    tableName = "habit_logs",
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habit_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["habit_id"]),
        Index(value = ["date_epoch_day"]),
        Index(value = ["habit_id", "date_epoch_day"], unique = true),
    ],
)
data class HabitLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "habit_id")
    val habitId: Long,

    @ColumnInfo(name = "date_epoch_day")
    val dateEpochDay: Long,

    @ColumnInfo(name = "date_start_millis")
    val dateStartMillis: Long,

    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = true,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "logged_at_millis")
    val loggedAtMillis: Long = 0L,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)
