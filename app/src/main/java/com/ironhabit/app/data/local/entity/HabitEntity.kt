package com.ironhabit.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitFrequency

/**
 * `habits` 表：习惯定义。
 *
 * 索引：`is_active`（列表过滤）。
 */
@Entity(
    tableName = "habits",
    indices = [
        Index(value = ["is_active"]),
    ],
)
data class HabitEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "emoji")
    val emoji: String = "",

    @ColumnInfo(name = "color_hex")
    val colorHex: String = Habit.DEFAULT_COLOR_HEX,

    @ColumnInfo(name = "frequency")
    val frequency: HabitFrequency = HabitFrequency.DAILY,

    /** 星期掩码：bit0 = 周一 … bit6 = 周日。 */
    @ColumnInfo(name = "weekly_days_mask")
    val weeklyDaysMask: Int = 0x7F,

    @ColumnInfo(name = "reminder_enabled")
    val reminderEnabled: Boolean = false,

    @ColumnInfo(name = "reminder_hour")
    val reminderHour: Int = 0,

    @ColumnInfo(name = "reminder_minute")
    val reminderMinute: Int = 0,

    @ColumnInfo(name = "note")
    val note: String? = null,

    /** 目标数值。`null` = 纯勾选型习惯；非 `null` = 计量型（如每天 8 杯水）。 */
    @ColumnInfo(name = "target_value")
    val targetValue: Double? = null,

    /** 目标单位文案（杯 / 分钟 / 步）。 */
    @ColumnInfo(name = "target_unit")
    val targetUnit: String? = null,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)
