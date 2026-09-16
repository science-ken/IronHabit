package com.ironhabit.app.domain.model

/**
 * 一周（周一 00:00 起，到周日结束）的复盘数据。
 *
 * 纯数据：无 Android、无 IO、无随机。同输入必同输出 → 可 JVM 单测。
 *
 * ⚠️ **诚实边界**：这里的每个 `?` 都表示"真的没有数据"，**不用 0 冒充**。
 * 例如本周没记过体重 → [BodyReview.deltaKg] 是 `null`，而不是 `0f`
 * （`0f` 会被读成"体重没变"，那是编出来的结论）。
 *
 * @property weekStartEpochDay 该周周一，`LocalDate.toEpochDays()` 口径
 * @property weekEndEpochDay 该周周日，同上口径
 * @property training 训练汇总
 * @property body 体重汇总
 * @property diet 饮食汇总
 * @property days 逐日逐条明细（导出数据包用；界面也可用来摊开看每天练了什么）
 * @property notes 数据不足的诚实说明（顺序固定：CHECKIN → RPE → WEIGHT → DIET → WEEK_IN_PROGRESS）
 */
data class WeeklyReview(
    val weekStartEpochDay: Long,
    val weekEndEpochDay: Long,
    val training: TrainingReview,
    val body: BodyReview,
    val diet: DietReview,
    val days: List<WeekDayDetail> = emptyList(),
    val notes: List<ReviewNote> = emptyList(),
)

/**
 * 训练汇总。
 *
 * @property plannedDays 计划里排了几天（`planRepository.observePlannedWeekdays()` 的个数）
 * @property completedDays 本周有打卡的天数（同一天多条打卡算一天）
 * @property totalVolumeKg 总容量 = Σ(weightKg × completedReps × completedSets)，**只统计有重量的记录**；无记录 = `0f`
 * @property totalSets 完成的总组数 = Σ completedSets
 * @property avgRpe 有 RPE 的记录的平均值（四舍五入保留 1 位小数）；一条都没有 = `null`
 * @property progressed 本周有记录、且比上周重的动作（按动作名排序）
 * @property stalled 本周有记录、且停滞 ≥ [STALLED_WEEKS_THRESHOLD] 周的动作（停滞周数倒序，再按动作名）
 */
data class TrainingReview(
    val plannedDays: Int,
    val completedDays: Int,
    val totalVolumeKg: Float,
    val totalSets: Int,
    val avgRpe: Float?,
    val progressed: List<ExerciseTrend>,
    val stalled: List<ExerciseTrend>,
) {
    companion object {
        /** 连续几周没进步算「停滞」。 */
        const val STALLED_WEEKS_THRESHOLD: Int = 3
    }
}

/**
 * 某个动作的周对周趋势。
 *
 * @property previousWeightKg 上周该动作的最大重量；上周没记录 = `null`
 * @property latestWeightKg 本周该动作的最大重量；本周没记录 = `null`
 * @property stagnantWeeks 连续几周有记录但没涨（`0` = 本周有进步）
 */
data class ExerciseTrend(
    val exerciseId: Long,
    val exerciseName: String,
    val previousWeightKg: Float?,
    val latestWeightKg: Float?,
    val stagnantWeeks: Int,
)

/**
 * 体重汇总。
 *
 * @property startWeightKg 本周最早的体重记录（没有 = `null`）
 * @property latestWeightKg 本周最新的体重记录（没有 = `null`）
 */
data class BodyReview(
    val startWeightKg: Float?,
    val latestWeightKg: Float?,
) {
    /** 本周体重变化；任一端缺失 = `null`（**不要**用 0 冒充"没变"）。 */
    val deltaKg: Float?
        get() = if (startWeightKg == null || latestWeightKg == null) {
            null
        } else {
            latestWeightKg - startWeightKg
        }
}

/**
 * 饮食汇总。
 *
 * @property loggedDays 本周"有饮食记录"的天数（当天启用状态的餐次里 kcal > 0）
 * @property avgKcal 有记录那几天的日均热量；没记录 = `null`
 * @property avgProteinG 有记录那几天的日均蛋白质；没记录 = `null`
 */
data class DietReview(
    val loggedDays: Int,
    val avgKcal: Int?,
    val avgProteinG: Int?,
)

/**
 * 某一天的明细。
 *
 * 一周 **7 天全都要出现**（没打卡的天 [items] 为空列表）—— 这样界面/导出都不需要"补空天"的逻辑。
 */
data class WeekDayDetail(
    val dateEpochDay: Long,
    val items: List<WeekItemDetail> = emptyList(),
)

/**
 * 一条打卡明细。
 *
 * @property exerciseName 动作名；**查不到名字的动作不会出现在这里**（不写"未知动作"占位）
 * @property sets 完成的组数（`CheckIn.completedSets`）
 * @property reps 每组次数（`CheckIn.completedReps`）
 * @property weightKg 重量；自重动作 = `null`
 * @property rpe 主观强度 `1..10`；未评级 = `null`
 * @property note 备注
 */
data class WeekItemDetail(
    val exerciseId: Long,
    val exerciseName: String,
    val sets: Int,
    val reps: Int,
    val weightKg: Float?,
    val rpe: Int?,
    val note: String?,
)

/**
 * 数据不足的诚实说明（UI 按这个渲染"为什么这里没数"，**不要**自己编文案）。
 */
enum class ReviewNote {
    /** 本周一条打卡都没有。 */
    NO_CHECKIN,

    /** 有打卡但都没有 RPE。 */
    NO_RPE,

    /** 本周没有体重记录。 */
    NO_WEIGHT,

    /** 本周没有饮食记录。 */
    NO_DIET,

    /** 这一周还没过完。 */
    WEEK_IN_PROGRESS,
}
