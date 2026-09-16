package com.ironhabit.app.domain.diet

import com.ironhabit.app.data.preset.BuiltInMealTemplates
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import kotlin.math.roundToInt

/**
 * 本地确定性**饮食规则引擎**（对应预览 `doDiet()`，见 `docs/schema-v3-meals.md` §7）。
 *
 * **纯函数**：零 Android / 零 IO / 零网络 / 零随机 —— 同输入必同输出 → **JVM 单测可完整覆盖**
 * （与本项目 `StreakCalculator` / `LocalRuleAdvisor` 同构）。
 *
 * ## 规则（系数集中为可调常量）
 * ① BMR（Mifflin-St Jeor）：男 `10·kg + 6.25·cm − 5·age + 5`，女 `… − 161`
 *   （与预览「BMR≈1720 kcal / 78kg」吻合）；② TDEE = BMR × 活动系数（训练日 1.55 / 休息日 1.375）；
 * ③ 目标热量 = TDEE × [Goal.kcalFactor]；④ 目标蛋白 = kg × 1.6（预览 78×1.6 ≈ 125g）；
 * ⑤ 按餐次比例拆分（由预览 4 餐精确反解：0.25 / 0.335 / 0.125 / 0.29，合计 1.0）；
 * ⑥ 按用户忌口（`UserProfile.dietaryAvoid`）过滤食物条目，并**按存活条目比例近似缩放**宏量
 *   （`tags ∩ 忌口 ≠ ∅` 的条目丢弃；见 [buildDraft]）。忌口为空集时**结果与本功能引入前逐字一致**。
 *
 * ## 降级（§7.5.3：默认值补全 + 出口钳制 + 显式提示，三重保险）
 * 任何输入下都产出 `[1200, 4000]` 内的有限热量 / `[50, 300]` 内的蛋白，**绝不为负 / NaN / 极低，绝不抛异常**。
 * 缺项补全：体重→70kg、性别→FEMALE（更保守）、年龄→30、身高→175、目标→MAINTAIN。
 */
object DietPlanGenerator {

    // ------------------------------------------------------------------
    // 集中可调常量（便于单人校准）
    // ------------------------------------------------------------------

    /** 缺省体重（kg）—— 体重是唯一真正必需的输入，缺时取中性成人值。 */
    const val DEFAULT_WEIGHT_KG: Double = 70.0

    /** 体重输入钳制下界（kg）。 */
    const val MIN_WEIGHT_KG: Double = 30.0

    /** 体重输入钳制上界（kg）。 */
    const val MAX_WEIGHT_KG: Double = 300.0

    /** 缺省年龄（岁）—— 预览实测锚点（175cm / 30 岁 / 男）。 */
    const val DEFAULT_AGE: Int = 30

    /** 缺省身高（cm）—— 预览实测锚点。 */
    const val DEFAULT_HEIGHT_CM: Int = 175

    /** 训练日活动系数。 */
    const val ACTIVITY_TRAINING: Double = 1.55

    /** 休息日活动系数。 */
    const val ACTIVITY_REST: Double = 1.375

    /** 目标热量钳制下界（kcal）。 */
    const val MIN_TARGET_KCAL: Double = 1200.0

    /** 目标热量钳制上界（kcal）。 */
    const val MAX_TARGET_KCAL: Double = 4000.0

    /** 蛋白质系数（g/kg）—— 与预览 1.6 精确吻合。 */
    const val PROTEIN_PER_KG: Double = 1.6

    /** 目标蛋白钳制下界（g）。 */
    const val MIN_TARGET_PROTEIN: Double = 50.0

    /** 目标蛋白钳制上界（g）。 */
    const val MAX_TARGET_PROTEIN: Double = 300.0

    /**
     * 餐次热量 / 蛋白比例（**由预览 F5 反解，合计 = 1.0**）。
     *
     * 用 `LinkedHashMap` 语义的 `mapOf` 保证遍历稳定（虽与算法无关）。
     */
    private val MEAL_RATIOS: Map<MealType, Double> = mapOf(
        MealType.BREAKFAST to 0.25,
        MealType.LUNCH to 0.335,
        MealType.SNACK to 0.125,
        MealType.DINNER to 0.29,
    )

    // ------------------------------------------------------------------
    // 纯函数 ①：每日目标
    // ------------------------------------------------------------------

    /**
     * 计算每日营养目标（内部完成 §7.5.3 的全部「补全 + 钳制」）。
     *
     * ⚠️ **分支只有一条**：无论档案是否完整都调用同一套规则（不设「空档案走粗算」的第二分支，
     * 否则会产出两套口径、难以单测，见 §7.5.4）。
     *
     * @param weightKg 当前体重（kg），`null` → 用 [DEFAULT_WEIGHT_KG]
     * @param gender 生理性别，`null` → 按 [Gender.FEMALE]（常数项更小 → 热量更保守）
     * @param age 年龄（岁），`null` → 用 [DEFAULT_AGE]
     * @param heightCm 身高（cm），`null` → 用 [DEFAULT_HEIGHT_CM]
     * @param goal 目标（其 [Goal.kcalFactor] 作用于 TDEE）
     * @param isTrainingDay 该日是否训练日（决定活动系数）
     * @return [DietTarget]；[DietTarget.usedDefaults] = 任一体征输入或体重缺失
     */
    fun dailyTarget(
        weightKg: Float?,
        gender: Gender?,
        age: Int?,
        heightCm: Int?,
        goal: Goal,
        isTrainingDay: Boolean,
    ): DietTarget {
        val usedDefaults: Boolean =
            weightKg == null || gender == null || age == null || heightCm == null

        val weight: Double = (weightKg?.toDouble() ?: DEFAULT_WEIGHT_KG)
            .coerceIn(MIN_WEIGHT_KG, MAX_WEIGHT_KG)
        val sex: Gender = gender ?: Gender.FEMALE
        val years: Int = age ?: DEFAULT_AGE
        val height: Int = heightCm ?: DEFAULT_HEIGHT_CM

        val bmr: Double = when (sex) {
            Gender.MALE -> 10.0 * weight + 6.25 * height - 5.0 * years + 5.0
            Gender.FEMALE -> 10.0 * weight + 6.25 * height - 5.0 * years - 161.0
        }
        val activity: Double = if (isTrainingDay) ACTIVITY_TRAINING else ACTIVITY_REST
        val tdee: Double = bmr * activity
        val targetKcal: Double = (tdee * goal.kcalFactor).coerceIn(MIN_TARGET_KCAL, MAX_TARGET_KCAL)
        val targetProtein: Double =
            (weight * PROTEIN_PER_KG).coerceIn(MIN_TARGET_PROTEIN, MAX_TARGET_PROTEIN)

        return DietTarget(
            targetKcal = targetKcal.roundToInt(),
            targetProtein = targetProtein.roundToInt(),
            usedDefaults = usedDefaults,
        )
    }

    // ------------------------------------------------------------------
    // 纯函数 ②：单餐草案（含忌口过滤 + 按比例缩放）
    // ------------------------------------------------------------------

    /**
     * 一餐草案 + 过滤计数。
     *
     * @property meal 该餐（条目已按忌口过滤、宏量已按比例缩放；`id = 0` 未落库）
     * @property filteredCount 该餐因忌口被**丢弃的条目数**（相对「本应使用的那份轮换模板」）
     */
    data class MealDraft(
        val meal: Meal,
        val filteredCount: Int,
    )

    /**
     * 由目标 + 餐次比例 + 内置模板，构造一餐草案（**确定性、可复现**）。
     *
     * 等价于 [buildDraft] 的便捷重载（丢弃过滤计数）；保留本签名以兼容既有调用方与单测。
     *
     * @param dietaryAvoid 用户忌口（空集 = 不过滤，结果与本功能引入前**逐字一致**）
     */
    fun buildMeal(
        epochDay: Long,
        mealType: MealType,
        target: DietTarget,
        createdAt: Long,
        dietaryAvoid: Set<DietRestriction> = emptySet(),
    ): Meal = buildDraft(epochDay, mealType, target, createdAt, dietaryAvoid).meal

    /**
     * 构造一餐草案 + 过滤计数（**确定性、可复现、零 Android**）。
     *
     * ## 忌口过滤（§7.5.2 `dietaryAvoid`）
     * 丢弃所有 `tags ∩ dietaryAvoid ≠ ∅` 的条目。
     *
     * ## 宏量口径（**近似，诚实登记**）
     * 热量 / 蛋白按 **(保留条目数 / 原条目数)** **等比缩放并四舍五入**
     * （`原条目数` = 最终采用的那份模板过滤前的条目数）。
     * 理由：条目本身**不带各自的 kcal/蛋白**（见设计文档 §2.2①），无法精确扣减，
     * 故用「条目存活比例」近似 —— 目的是**不把没吃到的食物算进当日总量**。
     * 这是近似值，非营养学精算。
     *
     * ## 兜底（某餐被过滤空时，按顺序尝试）
     * 1. 先取本应使用的那份轮换模板并过滤；**非空**即采用；
     * 2. 为空 → 按**轮换顺序**取同餐次的下 N 份模板（仍过滤），采用**第一份过滤后非空**的；
     * 3. 全部为空 → **保留空条目**（**不删餐次、不崩**），宏量归 0。
     *
     * 任何输入下都不产生 NaN / 负值（`factor` 有 0 分母保护）。
     *
     * @param epochDay 日期口径（驱动模板轮换）
     * @param mealType 餐次
     * @param target 当日目标
     * @param createdAt 创建时间戳（UTC 毫秒；由调用方注入以保持纯函数无时钟依赖）
     * @param dietaryAvoid 用户忌口（空集 = 不过滤）
     * @param templates 模板库（默认取内置库；单测可注入以覆盖「整餐过滤空」路径）
     */
    fun buildDraft(
        epochDay: Long,
        mealType: MealType,
        target: DietTarget,
        createdAt: Long,
        dietaryAvoid: Set<DietRestriction>,
        templates: List<List<BuiltInMealTemplates.TaggedItem>> = BuiltInMealTemplates.forType(mealType),
    ): MealDraft {
        val size: Int = templates.size
        // Math.floorMod 正确处理负的 epochDay（1970 前），保证下标恒落 [0, size)。
        val startIndex: Int = Math.floorMod(epochDay + mealType.ordinal, size)
        val primary: List<BuiltInMealTemplates.TaggedItem> = templates[startIndex]
        val primaryKept: List<BuiltInMealTemplates.TaggedItem> = primary.filter { survives(it, dietaryAvoid) }
        val filteredCount: Int = primary.size - primaryKept.size

        // 兜底：本份过滤空时，按轮换顺序找第一份「过滤后仍非空」的模板。
        var chosenOriginal: List<BuiltInMealTemplates.TaggedItem> = primary
        var chosenKept: List<BuiltInMealTemplates.TaggedItem> = primaryKept
        if (primaryKept.isEmpty()) {
            for (offset in 1 until size) {
                val candidate: List<BuiltInMealTemplates.TaggedItem> = templates[(startIndex + offset) % size]
                val kept: List<BuiltInMealTemplates.TaggedItem> = candidate.filter { survives(it, dietaryAvoid) }
                if (kept.isNotEmpty()) {
                    chosenOriginal = candidate
                    chosenKept = kept
                    break
                }
            }
            // 全空 → 保留空条目（不删餐次、不崩）。
        }

        val factor: Double =
            if (chosenOriginal.isEmpty()) 0.0 else chosenKept.size.toDouble() / chosenOriginal.size
        val ratio: Double = ratioOf(mealType)

        return MealDraft(
            meal = Meal(
                dateEpochDay = epochDay,
                mealType = mealType,
                items = chosenKept.map { item -> item.text },
                kcal = (target.targetKcal * ratio * factor).roundToInt(),
                proteinG = target.targetProtein * ratio * factor,
                isCompleted = false,
                sortOrder = mealType.ordinal,
                isActive = true,
                isUserEdited = false,
                createdAt = createdAt,
            ),
            filteredCount = filteredCount,
        )
    }

    /** 条目是否在忌口下「存活」：其标签与忌口无交集即为存活（空忌口 → 全存活）。 */
    private fun survives(
        item: BuiltInMealTemplates.TaggedItem,
        dietaryAvoid: Set<DietRestriction>,
    ): Boolean = item.tags.none { tag -> tag in dietaryAvoid }

    /** 餐次比例（未知餐次回落 `0.0`，防御性；当前 4 餐全部有值）。 */
    fun ratioOf(mealType: MealType): Double = MEAL_RATIOS[mealType] ?: 0.0
}
