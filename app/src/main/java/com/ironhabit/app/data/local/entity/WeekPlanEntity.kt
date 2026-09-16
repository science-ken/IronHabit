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
 * 索引：`day_of_week`、`exercise_id`、
 * `(day_of_week, exercise_id, week_start_epoch_day)` 唯一（同一天同动作、**同一周内**不重复）。
 *
 * ⚠️ **P3 起唯一键多了 `week_start_epoch_day`**：不加这一列，模板行与"本周专属行"会撞同一个
 * 唯一槽位（模板和某周各要一条同「天 × 动作」是合法状态）。加了之后：
 * - 模板行 `week_start_epoch_day = 0`（哨兵，见 `WeekPlan.TEMPLATE_WEEK_START`），
 *   同一「天 × 动作」**仍然只能有一条模板**（`0` 不是 NULL，SQLite 会正常比较）；
 * - 某周专属行写真实的周一 epochDay，与模板行、与别的周都互不冲突。
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
        Index(
            value = ["day_of_week", "exercise_id", "week_start_epoch_day"],
            unique = true,
        ),
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

    /** 行级：本条（某天 × 某动作）被用户手动改过 → AI 生成时整行跳过（含软删除行）。 */
    @ColumnInfo(name = "is_user_edited")
    val isUserEdited: Boolean = false,

    /**
     * 这一条属于哪一周（P3）：`0` = 模板（每周循环，P3 之前的行为），> 0 = 只属于那一周（周一 epochDay）。
     *
     * 用哨兵 `0` 而不是 NULL —— 原因见本文件类注释（SQLite 里 NULL 在唯一索引中互不相等，
     * 用 NULL 会允许同「天 × 动作」出现多条模板行）。
     */
    @ColumnInfo(name = "week_start_epoch_day")
    val weekStartEpochDay: Long = 0L,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)
