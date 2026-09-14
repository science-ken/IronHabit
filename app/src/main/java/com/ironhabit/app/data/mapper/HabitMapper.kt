package com.ironhabit.app.data.mapper

import com.ironhabit.app.data.local.entity.HabitEntity
import com.ironhabit.app.data.local.entity.HabitLogEntity
import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitLog

/**
 * `HabitEntity ⇄ domain.Habit` 与 `HabitLogEntity ⇄ domain.HabitLog` 互转。
 */
object HabitMapper {

    // ---------------- Habit ----------------

    /** 习惯实体 → 领域模型（未开启提醒时提醒时间视作未设置）。 */
    fun toDomain(entity: HabitEntity): Habit = Habit(
        id = entity.id,
        name = entity.name,
        emoji = entity.emoji,
        colorHex = entity.colorHex,
        frequency = entity.frequency,
        weeklyDaysMask = entity.weeklyDaysMask,
        reminderEnabled = entity.reminderEnabled,
        reminderHour = entity.reminderHour.takeIf { entity.reminderEnabled },
        reminderMinute = entity.reminderMinute.takeIf { entity.reminderEnabled },
        isActive = entity.isActive,
        sortOrder = entity.sortOrder,
        createdAt = entity.createdAt,
    )

    /** 习惯领域模型 → 实体。 */
    fun toEntity(domain: Habit): HabitEntity = HabitEntity(
        id = domain.id,
        name = domain.name,
        emoji = domain.emoji,
        colorHex = domain.colorHex,
        frequency = domain.frequency,
        weeklyDaysMask = domain.weeklyDaysMask,
        reminderEnabled = domain.reminderEnabled,
        reminderHour = domain.reminderHour ?: 0,
        reminderMinute = domain.reminderMinute ?: 0,
        isActive = domain.isActive,
        sortOrder = domain.sortOrder,
        createdAt = domain.createdAt,
    )

    // ---------------- HabitLog ----------------

    /** 习惯日志实体 → 领域模型。 */
    fun toDomain(entity: HabitLogEntity): HabitLog = HabitLog(
        id = entity.id,
        habitId = entity.habitId,
        dateEpochDay = entity.dateEpochDay,
        dateStartMillis = entity.dateStartMillis,
        isCompleted = entity.isCompleted,
        note = entity.note,
        loggedAtMillis = entity.loggedAtMillis,
        createdAt = entity.createdAt,
    )

    /** 习惯日志领域模型 → 实体。 */
    fun toEntity(domain: HabitLog): HabitLogEntity = HabitLogEntity(
        id = domain.id,
        habitId = domain.habitId,
        dateEpochDay = domain.dateEpochDay,
        dateStartMillis = domain.dateStartMillis,
        isCompleted = domain.isCompleted,
        note = domain.note,
        loggedAtMillis = domain.loggedAtMillis,
        createdAt = domain.createdAt,
    )
}
