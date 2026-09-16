package com.ironhabit.app.domain.diet

import com.ironhabit.app.data.preset.BuiltInMealTemplates
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
 * ⑤ 按餐次比例拆分（由预览 4 餐精确反解：0.25 / 0.335 / 0.125 / 0.29，合计 1.0）。
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
    // 纯函数 ②：单餐草案
    // ------------------------------------------------------------------

    /**
     * 由目标 + 餐次比例 + 内置模板，构造一餐草案（**确定性、可复现**）。
     *
     * 热量 = `targetKcal × ratio` 四舍五入；蛋白 = `targetProtein × ratio`（不四舍五入，保留精度）；
     * 条目 = 内置模板里 `((epochDay + mealType.ordinal) % templates.size)` 那一套。
     *
     * @param epochDay 日期口径（驱动模板轮换）
     * @param mealType 餐次
     * @param target 当日目标
     * @param createdAt 创建时间戳（UTC 毫秒；由调用方注入以保持纯函数无时钟依赖）
     * @return 尚未落库的 [Meal]（`id = 0`，`isActive = true`，`isUserEdited = false`，`isCompleted = false`）
     */
    fun buildMeal(
        epochDay: Long,
        mealType: MealType,
        target: DietTarget,
        createdAt: Long,
    ): Meal {
        val ratio: Double = ratioOf(mealType)
        val templates: List<List<String>> = BuiltInMealTemplates.forType(mealType)
        // Math.floorMod 正确处理负的 epochDay（1970 前），保证下标恒落 [0, size)。
        val index: Int = Math.floorMod(epochDay + mealType.ordinal, templates.size)
        return Meal(
            dateEpochDay = epochDay,
            mealType = mealType,
            items = templates[index],
            kcal = (target.targetKcal * ratio).roundToInt(),
            proteinG = target.targetProtein * ratio,
            isCompleted = false,
            sortOrder = mealType.ordinal,
            isActive = true,
            isUserEdited = false,
            createdAt = createdAt,
        )
    }

    /** 餐次比例（未知餐次回落 `0.0`，防御性；当前 4 餐全部有值）。 */
    fun ratioOf(mealType: MealType): Double = MEAL_RATIOS[mealType] ?: 0.0
}
