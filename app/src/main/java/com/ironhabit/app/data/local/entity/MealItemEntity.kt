package com.ironhabit.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `meal_items` 表：**一行 = 用户真的吃了这一样东西**。
 *
 * ⚠️ 这条语义是整个设计的承重墙（见 `.scratch/ironhabit-diet-food-log/spec.md` §1）：
 * AI 生成的建议**永远不进这张表**，它们存在 `meals.items_text` 里当"计划"。
 * 一旦让建议也进来，就是训练区 2026-09-20 刚修掉的
 * 「`week_plans` 排了课被当成 `check_ins` 练了」在饮食区重演一遍。
 *
 * ## 为什么营养值是快照列而不是外键现算
 * 与 [CheckInEntity] 存 `weight_kg`（当时举了多少）而不是回查计划同一个理由：
 * 改食物定义不能改写历史。所以这里同时存 [foodName] —— 食物被停用甚至将来被删掉，
 * 这一行仍然能自解释"当时吃的是米饭、150 克"。
 *
 * ## 外键为什么是 SET NULL 而不是 CASCADE
 * CASCADE 会让"删掉一个食物"顺手抹掉吃过它的历史记录 —— 那是丢用户数据。
 * SET NULL 之后 [foodId] 空了，但名称与营养快照还在，历史完好，只是点不进详情。
 * （本库目前**根本不提供**物理删除，见 `FoodDao.deactivate`，所以这只是防线。）
 */
@Entity(
    tableName = "meal_items",
    foreignKeys = [
        ForeignKey(
            entity = MealEntity::class,
            parentColumns = ["id"],
            childColumns = ["meal_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FoodEntity::class,
            parentColumns = ["id"],
            childColumns = ["food_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["meal_id"]),
        Index(value = ["food_id"]),
    ],
)
data class MealItemEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** 属于哪一餐（`meals.id`）。一餐可以挂任意多条。 */
    @ColumnInfo(name = "meal_id")
    val mealId: Long,

    /** 指向食物库；食物被物理删除后置 `null`，靠 [foodName] 与快照自解释。 */
    @ColumnInfo(name = "food_id")
    val foodId: Long? = null,

    /** **名称快照**：删掉食物、改名之后，历史仍然读得懂。 */
    @ColumnInfo(name = "food_name")
    val foodName: String,

    /** 实际克数（由"份数 × 每份克数"或直接输入的克数换算而来）。营养快照以它为准。 */
    @ColumnInfo(name = "grams")
    val grams: Double,

    /**
     * 用户当时选的份单位（"碗 / 个 / 勺"）；`null` = 他是直接按克填的。
     *
     * 为什么连份也要快照而不是回查 `food_servings`：
     * 他要的是"我当时记的是**一碗**"，而不是"按现在的定义这算几碗"。
     * 用户之后把「一碗」从 200g 改成 250g，历史条目仍然应该显示"1碗"、
     * 营养也仍然按**当时的 200g** 计 —— 回查会把旧记录一起改掉。
     */
    @ColumnInfo(name = "serving_unit")
    val servingUnit: String? = null,

    /** 份数（可与 [servingUnit] 一起为 `null`）。允许小数：半个鸡蛋 = 0.5。 */
    @ColumnInfo(name = "serving_count")
    val servingCount: Double? = null,

    /** 下面四项是**落库时算好的快照**，查询时不再回查 `foods`。 */
    @ColumnInfo(name = "kcal")
    val kcal: Int,

    @ColumnInfo(name = "protein_g")
    val proteinG: Double,

    @ColumnInfo(name = "carbs_g")
    val carbsG: Double,

    @ColumnInfo(name = "fat_g")
    val fatG: Double,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)
