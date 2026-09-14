package com.ironhabit.app.data.local.entity

import androidx.room.TypeConverter
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.HabitFrequency
import kotlinx.datetime.LocalDate

/**
 * Room `@TypeConverter`：枚举 ↔ `String`、[LocalDate] ↔ `Long`。
 *
 * 数据库列一律以文本存枚举名、以 `LocalDate.toEpochDays()` 存日期，
 * 与架构 §7.3「日期唯一口径」一致。
 */
class Converters {

    // ---- LocalDate ↔ epochDay ----

    @TypeConverter
    fun fromLocalDate(date: LocalDate?): Long? = date?.toEpochDays()?.toLong()

    @TypeConverter
    fun toLocalDate(epochDay: Long?): LocalDate? = epochDay?.let { LocalDate.fromEpochDays(it.toInt()) }

    // ---- ExerciseCategory ↔ String ----

    @TypeConverter
    fun fromExerciseCategory(category: ExerciseCategory?): String? = category?.name

    @TypeConverter
    fun toExerciseCategory(value: String?): ExerciseCategory? =
        value?.let { runCatching { ExerciseCategory.valueOf(it) }.getOrNull() }

    // ---- HabitFrequency ↔ String ----

    @TypeConverter
    fun fromHabitFrequency(frequency: HabitFrequency?): String? = frequency?.name

    @TypeConverter
    fun toHabitFrequency(value: String?): HabitFrequency? =
        value?.let { runCatching { HabitFrequency.valueOf(it) }.getOrNull() }

    // ---- BodyMetricType ↔ String ----

    @TypeConverter
    fun fromBodyMetricType(type: BodyMetricType?): String? = type?.name

    @TypeConverter
    fun toBodyMetricType(value: String?): BodyMetricType? =
        value?.let { runCatching { BodyMetricType.valueOf(it) }.getOrNull() }
}
