package com.ironhabit.app.domain.model

/**
 * 一餐里的**一个条目** = 用户真的吃了这么一样东西。
 *
 * ⚠️ 语义承重墙：这张表**只装实际吃下的**。AI 生成的建议留在 [Meal.items]
 * （那串文字），用户点「吃了它」才会复制成一条 [MealItem]。
 * 让建议直接进这里 = 训练区刚修掉的「排了计划当成练了」在饮食区重演。
 *
 * @property nutrition **落库时算好的快照**，不回查 [Food]。
 *   改食物定义、把食物停用甚至删掉，都不会改写这条历史记录
 *   （与 `CheckIn.weightKg` 存"当时举了多少"同一原则）。
 * @property servingUnit / servingCount 当时用的份与份数（也是快照）。
 *   `null` = 用户直接按克填的。存在的意义是界面要显示"1 碗"而不是"150 g"，
 *   而且**用户以后把一碗改成 250g，这条历史仍然该显示 1 碗、仍按当时的克数计**。
 */
data class MealItem(
    val id: Long = 0L,
    val mealId: Long,
    /** 指向食物库；食物被物理删除后置 `null`，靠 [foodName] 与 [nutrition] 自解释。 */
    val foodId: Long? = null,
    val foodName: String,
    val grams: Double,
    val servingUnit: String? = null,
    val servingCount: Double? = null,
    val nutrition: FoodNutrition = FoodNutrition(0, 0.0, 0.0, 0.0),
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
) {

    /** 界面上的份量写法：有份就写"1碗"，否则写克数。 */
    val portionLabel: String
        get() {
            val unit: String? = servingUnit?.takeIf { it.isNotBlank() }
            val count: Double? = servingCount?.takeIf { it > 0.0 }
            if (unit == null || count == null) return "${grams.roundToIntOrHalf()} g"
            val countText: String = if (count % 1.0 == 0.0) count.toLong().toString() else count.toString()
            return "$countText$unit"
        }
}

/** 显示用：整数不带小数，半份保留一位（`150.0` → `150`，`75.5` → `75.5`）。 */
private fun Double.roundToIntOrHalf(): String =
    if (kotlin.math.abs(this - kotlin.math.round(this)) < 0.05) kotlin.math.round(this).toInt().toString()
    else this.toString()
