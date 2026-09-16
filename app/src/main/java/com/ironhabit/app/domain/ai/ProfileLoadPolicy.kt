package com.ironhabit.app.domain.ai

import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.ProfileLimits
import com.ironhabit.app.domain.model.UserProfile

/**
 * 触发了哪条「档案 → 训练量」规则。
 *
 * **不含文案**：UI 层按 `basis_*` 资源名映射（见 `AiCoachScreen.basisKeyRes`），
 * 规则层只产出"哪一条生效了"，绝不内联中文（架构 §7.5）。
 */
enum class PolicyReason {
    /** 目标决定了组次区间 / 加重节奏（恒定触发）。 */
    GOAL_VOLUME,

    /** 体脂偏高 → 有氧比例上调。 */
    BODY_FAT_HIGH,

    /** 体脂偏低且目标是增肌 → 有氧比例下调。 */
    BODY_FAT_LOW,

    /** 当前体重高于目标体重 → 有氧比例上调。 */
    WEIGHT_TO_CUT,

    /** 离目标体重还差一截 → 单日容量上调。 */
    WEIGHT_TO_GAIN,

    /** 年龄偏大 → 单日动作数下调，优先保证恢复。 */
    AGE_VOLUME,

    /** 年龄已知且 ≥ 40 → 给出恢复建议（组间休息 / 训练间隔）。 */
    RECOVERY_AGE,
}

/**
 * 「档案 → 训练量参数」的结果（**纯数据**）。
 *
 * 这是 P1 的核心：把档案里的字段变成**规则引擎真正会用的数字**，
 * 而不是只写在"生成依据"里凑字数。
 */
data class LoadPolicy(
    /** 每周训练天数（`3–6`）。 */
    val trainingDaysPerWeek: Int,
    /** 每天最多排几个动作。 */
    val itemsPerDay: Int,
    /** 目标组数区间（含两端）。 */
    val setsRange: IntRange,
    /** 目标次数区间（含两端）。 */
    val repsRange: IntRange,
    /** 每周至少几个有氧动作。 */
    val cardioPerWeek: Int,
    /** 渐进超负荷的重量步长（kg）。 */
    val weightStepKg: Float,
    /** 本次生效了哪些规则（固定顺序，见 [ProfileLoadPolicy.of]）。 */
    val reasons: List<PolicyReason> = emptyList(),
)

/**
 * 档案 → 训练量参数（**纯函数**：零 IO / 零随机 / 零 Android / 零文案）。
 *
 * ## 映射表（每条都有单测；改数值 = 改行为，必须同时改测试）
 *
 * | 输入 | 输出 |
 * |------|------|
 * | 目标 [Goal.BULK] | 每组 `8–12` 次 · 每个动作 `3–4` 组 · 每周 1 个有氧 · 加重步长 `2.5kg` |
 * | 目标 [Goal.RECOMP] | `10–12` 次 · `3–4` 组 · 2 个有氧 · `2.5kg` |
 * | 目标 [Goal.MAINTAIN] | `10–12` 次 · `3–3` 组 · 1 个有氧 · `2.5kg` |
 * | 目标 [Goal.SHAPE] | `12–15` 次 · `3–3` 组 · 2 个有氧 · `1.25kg` |
 * | 目标 [Goal.CUT] | `12–15` 次 · `3–3` 组 · 3 个有氧 · `1.25kg` |
 * | 体脂 ≥ 男 25% / 女 32%（**性别须已知**） | 有氧 +1 |
 * | 体脂 ≤ 男 12% / 女 20% 且目标为增肌类 | 有氧 −1（下限 1） |
 * | 当前体重 − 目标体重 ≥ 1kg（即要减） | 有氧 +1 |
 * | 目标体重 − 当前体重 ≥ 3kg（即要增） | 组数区间上限 +1 |
 * | 年龄 ≥ 50 | 每天动作数 −1（下限 3） |
 * | 年龄 ≥ 40 | 记一条恢复建议 |
 *
 * ## 诚实登记的边界（**没有**凭空造规则）
 * - **性别未知时不做体脂判断**（男女阈值差 7 个百分点，猜错不如不判）；
 * - **体重数据缺失时（没记过体重）不做体重/目标体重判断** —— 不用 0 或"标准体重"冒充；
 * - **身高不进训练规则**：身高对训练量与恢复没有可靠依据，它只用于 BMR / 饮食估算。
 *   （预览稿里写过"身高影响默认量级"，这里**不做**——宁可少一条，也不编一条。）
 */
object ProfileLoadPolicy {

    /** 单日动作数的默认值（未触发任何下调规则时）。 */
    const val DEFAULT_ITEMS_PER_DAY: Int = 4

    /** 一个目标的基准参数。 */
    private data class GoalBaseline(
        val sets: IntRange,
        val reps: IntRange,
        val cardio: Int,
        val stepKg: Float,
    )

    private val GOAL_BASELINE: Map<Goal, GoalBaseline> = mapOf(
        Goal.BULK to GoalBaseline(sets = 3..4, reps = 8..12, cardio = 1, stepKg = 2.5f),
        Goal.RECOMP to GoalBaseline(sets = 3..4, reps = 10..12, cardio = 2, stepKg = 2.5f),
        Goal.MAINTAIN to GoalBaseline(sets = 3..3, reps = 10..12, cardio = 1, stepKg = 2.5f),
        Goal.SHAPE to GoalBaseline(sets = 3..3, reps = 12..15, cardio = 2, stepKg = 1.25f),
        Goal.CUT to GoalBaseline(sets = 3..3, reps = 12..15, cardio = 3, stepKg = 1.25f),
    )

    /** 体脂"偏高"阈值（%）；男女分开，性别未知则完全不判。 */
    private const val MALE_BODY_FAT_HIGH: Float = 25f
    private const val MALE_BODY_FAT_LOW: Float = 12f
    private const val FEMALE_BODY_FAT_HIGH: Float = 32f
    private const val FEMALE_BODY_FAT_LOW: Float = 20f

    /** 体重差判定阈值（kg）：差 1kg 以内视为"已经到位"，不调有氧。 */
    private const val WEIGHT_DIFF_EPSILON: Float = 1f

    /** "还要增"多少 kg 才上调单日容量。 */
    private const val WEIGHT_GAIN_VOLUME_THRESHOLD: Float = 3f

    /** 年龄阈值：≥ 此值下调单日动作数。 */
    private const val AGE_LOWER_VOLUME: Int = 50

    /** 年龄阈值：≥ 此值给恢复建议。 */
    private const val AGE_RECOVERY_NOTE: Int = 40

    /** 合法的"要减脂"目标（低体脂 + 这些目标 → 降有氧）。 */
    private val BULK_LIKE_GOALS: Set<Goal> = setOf(Goal.BULK, Goal.RECOMP)

    private val ITEMS_PER_DAY_RANGE: IntRange = 3..5
    private val SETS_RANGE: IntRange = 2..5

    /**
     * 计算训练量参数。
     *
     * @param profile      用户档案
     * @param bodyWeightKg 当前体重（来自 `body_metrics` 最新一条）；`null` = 用户没记过体重
     *   → **跳过**体重相关判断（不猜、不用 0 代替）
     */
    fun of(profile: UserProfile, bodyWeightKg: Float? = null): LoadPolicy {
        val baseline: GoalBaseline = GOAL_BASELINE.getValue(profile.goal)
        val reasons: MutableList<PolicyReason> = ArrayList()

        // ① 目标：恒定生效（组次区间 / 有氧 / 加重步长都由它定基准）。
        reasons += PolicyReason.GOAL_VOLUME
        var itemsPerDay: Int = DEFAULT_ITEMS_PER_DAY
        var sets: IntRange = baseline.sets
        var cardio: Int = baseline.cardio

        // ② 体脂（性别未知 → 不判）。
        val bodyFat: Float? = profile.bodyFatPct
        val gender: Gender? = profile.gender
        if (bodyFat != null && gender != null) {
            val high: Float = if (gender == Gender.MALE) MALE_BODY_FAT_HIGH else FEMALE_BODY_FAT_HIGH
            val low: Float = if (gender == Gender.MALE) MALE_BODY_FAT_LOW else FEMALE_BODY_FAT_LOW
            when {
                bodyFat >= high -> {
                    cardio += 1
                    reasons += PolicyReason.BODY_FAT_HIGH
                }

                bodyFat <= low && profile.goal in BULK_LIKE_GOALS -> {
                    cardio -= 1
                    reasons += PolicyReason.BODY_FAT_LOW
                }
            }
        }

        // ③ 体重 vs 目标体重（任一端缺失 → 不判；体重 ≤ 0 视为脏数据 → 不判）。
        val targetWeight: Float? = profile.goalWeightKg
        if (bodyWeightKg != null && targetWeight != null && bodyWeightKg > 0f) {
            val diff: Float = targetWeight - bodyWeightKg
            when {
                diff <= -WEIGHT_DIFF_EPSILON -> {
                    cardio += 1
                    reasons += PolicyReason.WEIGHT_TO_CUT
                }

                diff >= WEIGHT_GAIN_VOLUME_THRESHOLD -> {
                    sets = sets.first..(sets.last + 1)
                    reasons += PolicyReason.WEIGHT_TO_GAIN
                }
            }
        }

        // ④ 年龄：影响单日量与恢复建议。
        val age: Int? = profile.age
        if (age != null) {
            if (age >= AGE_LOWER_VOLUME) {
                itemsPerDay -= 1
                reasons += PolicyReason.AGE_VOLUME
            }
            if (age >= AGE_RECOVERY_NOTE) {
                reasons += PolicyReason.RECOVERY_AGE
            }
        }

        // ⑤ 统一钳制（任何输入都不产出荒谬参数）。
        val setsLow: Int = sets.first.coerceIn(SETS_RANGE.first, SETS_RANGE.last)
        val setsHigh: Int = sets.last.coerceIn(setsLow, SETS_RANGE.last)

        return LoadPolicy(
            trainingDaysPerWeek = ProfileLimits.coerceTrainingDaysPerWeek(profile.trainingDaysPerWeek),
            itemsPerDay = itemsPerDay.coerceIn(ITEMS_PER_DAY_RANGE.first, ITEMS_PER_DAY_RANGE.last),
            setsRange = setsLow..setsHigh,
            repsRange = baseline.reps,
            cardioPerWeek = cardio.coerceIn(MIN_CARDIO_PER_WEEK, MAX_CARDIO_PER_WEEK),
            weightStepKg = baseline.stepKg,
            reasons = reasons.toList(),
        )
    }

    /** 每周有氧动作数的合法下界（再减也不低于它 —— 有氧是健康底线，不是可选项）。 */
    private const val MIN_CARDIO_PER_WEEK: Int = 1

    /** 每周有氧动作数的合法上界（再多会挤掉力量训练的恢复）。 */
    private const val MAX_CARDIO_PER_WEEK: Int = 4
}
