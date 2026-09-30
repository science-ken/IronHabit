package com.ironhabit.app.data.local.entity

import androidx.room.TypeConverter
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseSource
import com.ironhabit.app.domain.model.HabitFrequency
import kotlinx.datetime.LocalDate

/**
 * Room `@TypeConverter`：枚举 ↔ `String`、[LocalDate] ↔ `Long`。
 *
 * 数据库列一律以文本存枚举名、以 `LocalDate.toEpochDays()` 存日期，
 * 与架构 §7.3「日期唯一口径」一致。
 *
 * ⚠️ **枚举回落必须给默认成员，不能给 `null`**（V3 报告 P3-1）：
 * 这四个枚举都映射到**非空**实体字段 —— 转换器返回 `null` 时，Room 构造数据类
 * 会在 Intrinsics 空检查上抛 NPE，表现为"这张表**所有查询**整表崩"。
 * 词表外字符串的现实来源是「未来版本新增枚举常量后，旧构建读新库」与 App 外写库；
 * `FoodSource` 已在 [FoodEntity] 里按同一思路做过安全兜底，这里是其余四枚的收口。
 * 回落成员选"最不伤语义"的：见各转换器注释。
 */
class Converters {

    // ---- LocalDate ↔ epochDay ----

    @TypeConverter
    fun fromLocalDate(date: LocalDate?): Long? = date?.toEpochDays()?.toLong()

    @TypeConverter
    fun toLocalDate(epochDay: Long?): LocalDate? = epochDay?.let { LocalDate.fromEpochDays(it.toInt()) }

    // ---- ExerciseCategory ↔ String ----

    /** 回落 `BODYWEIGHT`（自重无器械，猜错也最不可能卡住用户）。 */
    @TypeConverter
    fun fromExerciseCategory(category: ExerciseCategory?): String? = category?.name

    @TypeConverter
    fun toExerciseCategory(value: String?): ExerciseCategory? =
        value?.let { runCatching { ExerciseCategory.valueOf(it) }.getOrDefault(ExerciseCategory.BODYWEIGHT) }

    // ---- ExerciseSource ↔ String ----

    /** 回落 `CUSTOM`（来源不明就按"用户数据"对待：别被重新生成覆盖，也别显示成内置）。 */
    @TypeConverter
    fun fromExerciseSource(source: ExerciseSource?): String? = source?.name

    @TypeConverter
    fun toExerciseSource(value: String?): ExerciseSource? =
        value?.let { runCatching { ExerciseSource.valueOf(it) }.getOrDefault(ExerciseSource.CUSTOM) }

    // ---- HabitFrequency ↔ String ----

    /** 回落 `DAILY`（每天都该做 —— 漏比错严，回落到最保守的口径）。 */
    @TypeConverter
    fun fromHabitFrequency(frequency: HabitFrequency?): String? = frequency?.name

    @TypeConverter
    fun toHabitFrequency(value: String?): HabitFrequency? =
        value?.let { runCatching { HabitFrequency.valueOf(it) }.getOrDefault(HabitFrequency.DAILY) }

    // ---- BodyMetricType ↔ String ----

    /** 回落 `WEIGHT`（第一顺位的记录类型，词表外的历史行最可能是它）。 */
    @TypeConverter
    fun fromBodyMetricType(type: BodyMetricType?): String? = type?.name

    @TypeConverter
    fun toBodyMetricType(value: String?): BodyMetricType? =
        value?.let { runCatching { BodyMetricType.valueOf(it) }.getOrDefault(BodyMetricType.WEIGHT) }
}
