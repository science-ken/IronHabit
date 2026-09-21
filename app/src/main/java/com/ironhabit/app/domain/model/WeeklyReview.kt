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
 * @property days 逐日逐条明细（导出数据包 + 界面摊开看每天练了什么）
 * @property todayEpochDay 今天是哪天，用于把"进行中"那一格标出来；
 *   `null` = 调用方没给时钟基准，界面就不标今天（**不拿周一凑数**）
 * @property notes 数据不足的诚实说明（顺序固定：CHECKIN → RPE → WEIGHT → DIET → WEEK_IN_PROGRESS）
 */
data class WeeklyReview(
    val weekStartEpochDay: Long,
    val weekEndEpochDay: Long,
    val training: TrainingReview,
    val body: BodyReview,
    val diet: DietReview,
    val days: List<WeekDayDetail> = emptyList(),
    val todayEpochDay: Long? = null,
    val notes: List<ReviewNote> = emptyList(),
)

/**
 * 训练汇总。
 *
 * @property plannedDays 计划里排了几天（`planRepository.observePlannedWeekdays()` 的个数）
 * @property completedDays 本周有打卡的天数（同一天多条打卡算一天）
 * @property totalVolumeKg 总容量 = Σ(weightKg × completedReps × completedSets)，**只统计有重量的记录**；无记录 = `0f`
 * @property totalSets 完成的总组数 = Σ completedSets
 * @property plannedSets 计划总组数 = Σ targetSets，走 `observeEffectivePlanForWeek`
 *   （**不能**直接对 `week_plans` 求和：模板回落 / 逐天覆盖 / 软删行三条规则只在
 *   `WeekPlanWeekResolver` 有一份，绕开它「12/35」的分母会和今日页清单对不上）
 * @property avgRpe 有 RPE 的记录的平均值（四舍五入保留 1 位小数）；一条都没有 = `null`
 * @property progressed 本周有记录、且比上周重的动作（按动作名排序）
 * @property stalled 本周有记录、且停滞 ≥ [STALLED_WEEKS_THRESHOLD] 周的动作（停滞周数倒序，再按动作名）
 */
data class TrainingReview(
    val plannedDays: Int,
    val completedDays: Int,
    val totalVolumeKg: Float,
    val totalSets: Int,
    val plannedSets: Int = 0,
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
 * @property sampleCount 本周一共有几条体重记录
 */
data class BodyReview(
    val startWeightKg: Float?,
    val latestWeightKg: Float?,
    val sampleCount: Int = 0,
) {
    /**
     * 本周体重变化。
     *
     * ⚠️ **只有一条记录时返回 `null`，不是 `0f`** —— 一次称重算不出"变化"，
     * 显示成 `0` 会被读成"体重没变"，那是编出来的结论（真机上就是这么发现的）。
     * 任一端缺失同样返回 `null`。
     */
    val deltaKg: Float?
        get() = if (sampleCount < MIN_SAMPLES_FOR_DELTA || startWeightKg == null || latestWeightKg == null) {
            null
        } else {
            latestWeightKg - startWeightKg
        }

    private companion object {
        /** 算"变化"至少要有两条记录。 */
        const val MIN_SAMPLES_FOR_DELTA: Int = 2
    }
}

/**
 * 饮食汇总。
 *
 * 口径（Q22 = **C**）：一天算不算"记过"、平均按多少算，看的都是**实际摄入**
 * （`MealIntakeCalculator`：有明细算明细，没明细但打了勾算粗记），
 * **不是** AI 排了多少餐 —— 排了计划一口没吃，历史上会被记成"记了 2400 kcal"。
 *
 * @property loggedDays 本周有实际记录的天数（当天既没明细也没打勾的不算）
 * @property avgKcal 有记录那几天的日均热量；没记录 = `null`
 * @property avgProteinG 有记录那几天的日均蛋白质；没记录 = `null`
 * @property preciseDays 其中**逐样记过明细**的天数。只要它小于 [loggedDays]，
 *   说明日均里掺了"打勾估的"天，界面就要在数字前面标「约」（Q31 = B）。
 *   判据用「小于」而不是「等于 0」：一周里记一天明细，不该把另外几天估的洗成准数。
 *   默认 0 是**故意偏保守**：漏填只会多标一次「约」，不会反过来把估的当准的。
 */
data class DietReview(
    val loggedDays: Int,
    val avgKcal: Int?,
    val avgProteinG: Int?,
    val preciseDays: Int = 0,
)

/**
 * 某一天的全部可展示事实。
 *
 * 一周 **7 天全都要出现**（没打卡的天 [items] 为空、[completedSets] 为 `0`）——
 * 这样界面/导出都不需要"补空天"的逻辑，星期格也永远有 7 格可点。
 *
 * ⚠️ 这些数字和周汇总**出自同一次遍历**（见 `BuildWeeklyReviewUseCase` 的不变量），
 * 不是日卡再查一遍仓库 —— 否则一次翻周可能出现"周卡说吃了 2199、日卡加起来不是"。
 *
 * @property plannedSets 那天计划排了几组（`0` = 真没排，不是"不知道"）
 * @property completedSets 那天实际完成几组 = Σ `CheckIn.completedSets`，
 *   **含查不到动作名的行**（那些行不进 [items]，但组数是真实发生的）
 * @property weightKg 那天的体重；一天最多一条（`BodyMetricEntity` 上 type+date 唯一索引），
 *   没称 = `null`
 * @property kcal 那天实际摄入热量；没记 = `null`（**不许渲染成 0**）
 * @property proteinG 那天实际蛋白质；没记 = `null`
 * @property dietPrecise 那天是不是逐样记的明细。`false` 且 [kcal] 非 `null`
 *   = 打了勾估的，界面要在数字前面标「约」。
 */
data class WeekDayDetail(
    val dateEpochDay: Long,
    val plannedSets: Int = 0,
    val completedSets: Int = 0,
    val weightKg: Float? = null,
    val kcal: Int? = null,
    val proteinG: Int? = null,
    val dietPrecise: Boolean = false,
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
