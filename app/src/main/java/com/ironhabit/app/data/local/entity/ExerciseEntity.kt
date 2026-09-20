package com.ironhabit.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseSource

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

    /**
     * ⚠️ 列名为**单数** `muscle_group`，但 v2 起内容为**有序 CSV**（如 `"胸部,肱三头肌"`），
     * **第一个 = 主肌群**，其后为辅。列名保留不复是因为 `RENAME COLUMN` 需 SQLite ≥ 3.25（API 28），
     * 而 `minSdk = 24`（架构 §4.1）。旧单值天然是长度 1 的合法 CSV。
     */
    @ColumnInfo(name = "muscle_group")
    val muscleGroup: String? = null,

    /**
     * 本动作**需要**的器械，v7 新增：`Equipment.name` 的**有序 CSV**（如 `"BARBELL"`、`"DUMBBELL,MACHINE"`）。
     *
     * - `null` / 空 = **未标注**（存量行、用户自建动作）→ 规则引擎回落到旧的"按分类猜"判据，
     *   绝不把"没标注"当成"不需要器械"；
     * - 复用 [com.ironhabit.app.domain.model.Equipment] 而非新造枚举：档案侧（用户有什么）与
     *   动作侧（动作要什么）必须共用一套词汇，否则匹配要在两张表之间手工对齐。
     */
    @ColumnInfo(name = "equipment")
    val equipment: String? = null,

    /** 三态来源（唯一真源，取代 [isBuiltIn]）。 */
    @ColumnInfo(name = "source")
    val source: ExerciseSource = ExerciseSource.BUILT_IN,

    /** 动作要点备注。 */
    @ColumnInfo(name = "note")
    val note: String? = null,

    @Deprecated("v2 起由 source 取代；列保留是因为 DROP COLUMN 需 API 31，而 minSdk = 24")
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
