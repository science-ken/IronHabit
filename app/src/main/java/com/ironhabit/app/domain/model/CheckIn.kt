package com.ironhabit.app.domain.model

/** 单动作单日最多支持的组数（`Int` 位宽 `- 1`，避开符号位）。 */
const val MAX_SETS: Int = 31

/**
 * 领域模型：训练打卡记录。
 *
 * 唯一性：同一动作同一天最多一条记录，由 `check_ins` 表的
 * `UNIQUE(exercise_id, date_epoch_day)` 约束保证「一键打卡」幂等。
 * 因此逐组完成明细**不能**用子表，改用 [completedSetsMask]（bitmask）。
 *
 * @property id 主键，自增；`0` 表示尚未落库
 * @property exerciseId 关联的动作 id
 * @property planId 来源计划条目 id，可空（**故意不建外键**，删除计划不抹掉历史）
 * @property dateEpochDay 日期口径 = `LocalDate.toEpochDays()`
 * @property dateStartMillis 当天 00:00 本地时间戳（区间查询/排序用）
 * @property completedSetsMask **唯一真源**：bit i（0-based）= 第 i+1 组是否完成，上限 [MAX_SETS] 组
 * @property rpe 主观强度 `1..10`，可空。渐进超负荷算法的唯一输入源
 * @property completedReps 实际每组次数
 * @property weightKg 重量（kg），可空
 * @property durationMinutes 时长（分钟），可空
 * @property notes 备注，可空
 * @property isQuick 一键完成 = `true`，补录 = `false`
 * @property loggedAtMillis 实际记录时刻（UTC 毫秒）
 * @property createdAt 创建时间戳（UTC 毫秒）
 */
data class CheckIn(
    val id: Long = 0L,
    val exerciseId: Long = 0L,
    val planId: Long? = null,
    val dateEpochDay: Long = 0L,
    val dateStartMillis: Long = 0L,
    val completedSetsMask: Int = 0,
    val rpe: Int? = null,
    val completedReps: Int = 0,
    val weightKg: Float? = null,
    val durationMinutes: Int? = null,
    val notes: String? = null,
    val isQuick: Boolean = false,
    val loggedAtMillis: Long = 0L,
    val createdAt: Long = 0L,
) {
    /**
     * 派生冗余列，**禁止独立写入**：恒等于 [completedSetsMask] 的置位数。
     *
     * 保留它是为了让 v1 已有的 `SUM(completed_sets)` 类 SQL 聚合一行都不用改
     * （SQLite 里做位计数极不现实）。
     */
    val completedSets: Int get() = completedSetsMask.countOneBits()

    /** 第 [setIndex] 组（0-based）是否完成。越界一律视为未完成。 */
    fun isSetCompleted(setIndex: Int): Boolean =
        setIndex in 0 until MAX_SETS && (completedSetsMask shr setIndex) and 1 == 1

    companion object {
        /**
         * 由「已完成组数」折算为低 n 位全 1 的 bitmask。
         *
         * 用于「一键打卡」「详细打卡」「补录」等只知总数、不知逐组明细的入口，
         * 保证不变量 `completedSets == completedSetsMask.countOneBits()` 成立。
         * `n` 上限钳制到 [MAX_SETS]，避免 `1 shl 31` 触到符号位（结果仍为 `Int.MAX_VALUE`）。
         */
        fun maskFromCount(count: Int): Int =
            if (count <= 0) 0 else (1 shl count.coerceAtMost(MAX_SETS)) - 1
    }
}
