package com.ironhabit.app.domain.ai.external

import com.ironhabit.app.domain.model.FoodNutrition
import com.ironhabit.app.domain.model.MealType

/**
 * 外部 AI 饮食文档解析出来的**一餐草案**。
 *
 * ## 数字从哪来（这条是整件事的承重墙）
 * [kcal] / [proteinG] **不是**文档给的，是 [com.ironhabit.app.domain.model.FoodNutritionCalculator]
 * 按食物库的每 100g 定义 × 克数现算出来的。文档里的营养数字一律不收 ——
 * 教练页上「数字本地算」那句话就是靠这条成立的。
 *
 * @property dayOfWeek `1..7`（1=周一），已钳制
 * @property entries 对上了食物库的那些条目，顺序 = 文档顺序
 * @property kcal 上面那些条目的合计 —— **不含**被挡掉的，所以 [unresolvedCount] > 0 时界面必须说"这餐少算了 N 条"
 * @property proteinG 同上（克）
 * @property unresolvedCount 因为"库里没有"而被排除的条数。**不含**撞忌口被挡的那些 ——
 *   忌口那条不是"少算了"，是"故意不算"，混进同一个数字会让那句话变成假话。
 */
data class ImportedMealDraft(
    val dayOfWeek: Int,
    val mealType: MealType,
    val entries: List<ImportedFoodEntry>,
    val kcal: Int,
    val proteinG: Double,
    val reason: String? = null,
    val unresolvedCount: Int = 0,
)

/** 一餐里对上了食物库的一条食物，连同本地算出来的营养量。 */
data class ImportedFoodEntry(
    val foodId: Long,
    val name: String,
    /** 已钳制到 [com.ironhabit.app.domain.model.InputLimits.MIN_SERVING_GRAMS] 起的克数。 */
    val grams: Int,
    val nutrition: FoodNutrition,
)

/**
 * 文档提到、但食物库里**完全没有**的食物：等用户在导入弹层里确认后才建库（刀 4）。
 *
 * ## 为什么四个数值全部可空
 * 建库门票取消后，"库里没有"不需要文档事先声明，于是这里常常**什么数值都没有**。
 * 空 = 界面上那一格必须用户填，不是 0：`0 kcal/100g` 和"还不知道"在营养数据里是两回事，
 * 写成 0 会让这一餐的合计**系统性偏小**，而勾了「吃了这餐」时那个偏小的数会直接进今日摄入。
 */
data class ImportedNewFood(
    val name: String,
    val kcalPer100g: Int? = null,
    val proteinPer100g: Double? = null,
    val carbsPer100g: Double? = null,
    val fatPer100g: Double? = null,
) {
    /** 数值齐了才能建库（缺任何一项都得让用户补，见 [ImportedNewFood] 的说明）。 */
    val hasCompleteNutrition: Boolean
        get() = kcalPer100g != null && proteinPer100g != null && carbsPer100g != null && fatPer100g != null
}
