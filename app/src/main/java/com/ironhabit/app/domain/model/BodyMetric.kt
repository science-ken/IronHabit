package com.ironhabit.app.domain.model

/**
 * 身体数据指标类型（P1）。
 */
enum class BodyMetricType {
    WEIGHT,
    BODY_FAT,
    MUSCLE_MASS,
    WAIST,
    CHEST,
    ARM,
    HIP,
}

/**
 * 领域模型：身体数据记录（体重 / 体脂等，P1）。
 *
 * @property id 主键，自增；`0` 表示尚未落库
 * @property type 指标类型
 * @property value 数值
 * @property unit 单位（`kg` / `%` / `cm` …）
 * @property dateEpochDay 日期口径 = `LocalDate.toEpochDays()`
 * @property dateStartMillis 当天 00:00 时间戳
 * @property note 备注，可空
 * @property createdAt 创建时间戳（UTC 毫秒）
 */
data class BodyMetric(
    val id: Long = 0L,
    val type: BodyMetricType = BodyMetricType.WEIGHT,
    val value: Float = 0f,
    val unit: String = "kg",
    val dateEpochDay: Long = 0L,
    val dateStartMillis: Long = 0L,
    val note: String? = null,
    val createdAt: Long = 0L,
)
