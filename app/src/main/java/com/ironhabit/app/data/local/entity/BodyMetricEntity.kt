package com.ironhabit.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ironhabit.app.domain.model.BodyMetricType

/**
 * `body_metrics` 表：体重 / 体脂等身体数据（P1）。
 *
 * 索引：`type`、`date_epoch_day`、`(type, date_epoch_day)` 唯一（同日同类型唯一）。
 */
@Entity(
    tableName = "body_metrics",
    indices = [
        Index(value = ["type"]),
        Index(value = ["date_epoch_day"]),
        Index(value = ["type", "date_epoch_day"], unique = true),
    ],
)
data class BodyMetricEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "type")
    val type: BodyMetricType,

    @ColumnInfo(name = "value")
    val value: Float,

    @ColumnInfo(name = "unit")
    val unit: String = "kg",

    @ColumnInfo(name = "date_epoch_day")
    val dateEpochDay: Long,

    @ColumnInfo(name = "date_start_millis")
    val dateStartMillis: Long,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)
