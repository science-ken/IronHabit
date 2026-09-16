package com.ironhabit.app.domain.model

/**
 * 领域模型：周计划条目（某动作排在周几）。
 *
 * @property id 主键，自增；`0` 表示尚未落库
 * @property exerciseId 关联的动作 id
 * @property dayOfWeek 星期，`1` = 周一 … `7` = 周日
 * @property targetSets 目标组数
 * @property targetReps 目标每组次数
 * @property targetWeightKg 目标重量（kg），可空
 * @property targetDurationMin 目标时长（分钟），可空（有氧用）
 * @property sortOrder 当日内排序
 * @property isActive 是否启用（`false` = 停用）
 * @property weekStartEpochDay **这一条属于哪一周**（P3 新增）：
 *   [TEMPLATE_WEEK_START]（`0`）= **模板**（每周循环，也就是 P3 之前的行为）；> 0 = **只属于那一周的周一**。
 *   取某天的计划时：**先找该周的专属行，没有就回落模板**。
 * @property createdAt 创建时间戳（UTC 毫秒）
 */
data class WeekPlan(
    val id: Long = 0L,
    val exerciseId: Long = 0L,
    val dayOfWeek: Int = 1,
    val targetSets: Int = 3,
    val targetReps: Int = 12,
    val targetWeightKg: Float? = null,
    val targetDurationMin: Int? = null,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    /**
     * 行级标记：这一条（某天 × 某动作）被用户手动改过。
     *
     * AI 重新生成整周计划时，`isUserEdited = true` 的行（**含软删除行**）
     * 完全跳过 —— 不更新、不插入、不复活。
     */
    val isUserEdited: Boolean = false,
    val weekStartEpochDay: Long = TEMPLATE_WEEK_START,
    val createdAt: Long = 0L,
) {

    /** 是否属于"模板"（每周循环的那一份）。 */
    val isTemplate: Boolean get() = weekStartEpochDay == TEMPLATE_WEEK_START

    companion object {
        /**
         * 「模板行」的 `week_start_epoch_day` 取值（P3）。
         *
         * ⚠️ **为什么不用 `NULL`**（预览稿写的是 `DEFAULT NULL`）：唯一索引
         * `UNIQUE(day_of_week, exercise_id, week_start_epoch_day)` 里 **SQLite 把 `NULL` 视为互不相等**，
         * 用 NULL 表示模板就会**允许同一「天 × 动作」出现多条模板行**，把"同槽位只有一行"这条
         * 已经上线的不变量打破。`0` 在真实数据里不可能出现（`epochDay 0` = 1970-01-01，没人会排那一周），
         * 所以拿它当哨兵是安全且自解释的。
         */
        const val TEMPLATE_WEEK_START: Long = 0L
    }
}
