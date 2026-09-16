package com.ironhabit.app.data.preset

import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.MealType

/**
 * 内置餐次模板常量（**纯数据**，按 [MealType] 分组）。
 *
 * 每套模板 = 一组**带过敏原标签**的食物条目（[TaggedItem]）；规则引擎用**确定性伪随机**
 * `((epochDay + mealType.ordinal) % templates.size)` 选取（见 `DietPlanGenerator`），
 * 保证「同一天同一餐永远算出同一条」→ 可复现、可单测。
 *
 * ## 过敏原标签口径（[TaggedItem.tags]）
 * 标签用于**按用户忌口（`UserProfile.dietaryAvoid`）过滤条目**（`DietPlanGenerator`）。
 * 判定遵循**保守原则：拿不准就不标**（宁可漏标，也不误伤正常推荐）：
 * - `PEANUT`（花生/坚果）：花生、核桃、杏仁等坚果类；
 * - `SEAFOOD`（海鲜）：鱼、虾、蟹、贝；
 * - `DAIRY`（乳制品）：牛奶、酸奶、奶酪、乳清；
 * - `GLUTEN`（麸质）：小麦 / 面 / 馒头 / 面包 / **燕麦**；
 * - `SPICY`（辛辣）：辣椒 / 辣酱 / 芥末；
 * - `ALCOHOL`（酒精）：含酒精。
 *
 * ## 诚实登记的边界（重要）
 * 1. 当前模板里**没有任何「辛辣 / 酒精」条目**（无辣椒/辣酱/芥末/含酒精食物），
 *    故 `SPICY` / `ALCOHOL` 两个忌口**过滤不到任何条目** —— 这是**正确行为**（不过滤 = 不误伤），
 *    不是缺陷；将来若要覆盖这两类，需**新增**相应条目（会改变既有输出）。
 * 2. **模糊项不标**：`杂粮粥 / 杂粮饭`（谷物种类不定）、`蛋白粉`（可能是乳清也可能是植物蛋白）
 *    一律**不打标签** —— 宁可漏标。
 * 3. 条目属**预置内容数据**（与 `BuiltInExercises` 的预置动作名同类，架构 §7.5 允许的唯一例外）；
 *    而**餐次名**（早餐/午餐/加餐/晚餐）属 UI 文案，走 `strings.xml`（`meal_breakfast` 等）。
 */
object BuiltInMealTemplates {

    /**
     * 一条带过敏原标签的食物条目。
     *
     * @property text 展示文本（条目内不含换行）
     * @property tags 该条目含有的过敏原（空集 = 无已知过敏原）
     */
    data class TaggedItem(
        val text: String,
        val tags: Set<DietRestriction> = emptySet(),
    )

    /** 早餐模板。 */
    val BREAKFAST: List<List<TaggedItem>> = listOf(
        listOf(
            TaggedItem("燕麦 50g + 脱脂牛奶 250ml", setOf(DietRestriction.GLUTEN, DietRestriction.DAIRY)),
            TaggedItem("水煮蛋 2 个"),
            TaggedItem("蓝莓 80g"),
        ),
        listOf(
            TaggedItem("全麦吐司 2 片", setOf(DietRestriction.GLUTEN)),
            TaggedItem("煎蛋 2 个"),
            TaggedItem("牛油果 1/2 个"),
        ),
        listOf(
            TaggedItem("无糖酸奶 200g", setOf(DietRestriction.DAIRY)),
            TaggedItem("香蕉 1 根"),
            TaggedItem("核桃 10g", setOf(DietRestriction.PEANUT)),
        ),
        listOf(
            TaggedItem("杂粮粥 1 碗"),
            TaggedItem("水煮蛋 1 个"),
            TaggedItem("小番茄 100g"),
        ),
    )

    /** 午餐模板。 */
    val LUNCH: List<List<TaggedItem>> = listOf(
        listOf(
            TaggedItem("糙米饭 150g"),
            TaggedItem("鸡胸肉 150g"),
            TaggedItem("西兰花 200g（少油）"),
        ),
        listOf(
            TaggedItem("藜麦 120g"),
            TaggedItem("清蒸鱼 150g", setOf(DietRestriction.SEAFOOD)),
            TaggedItem("凉拌菠菜 200g"),
        ),
        listOf(
            TaggedItem("全麦意面 100g", setOf(DietRestriction.GLUTEN)),
            TaggedItem("瘦牛肉 120g"),
            TaggedItem("混合蔬菜 200g"),
        ),
        listOf(
            TaggedItem("紫薯 150g"),
            TaggedItem("去皮鸡腿肉 130g"),
            TaggedItem("清炒时蔬 200g"),
        ),
    )

    /** 加餐模板。 */
    val SNACK: List<List<TaggedItem>> = listOf(
        listOf(
            TaggedItem("无糖希腊酸奶 150g", setOf(DietRestriction.DAIRY)),
            TaggedItem("杏仁 15g", setOf(DietRestriction.PEANUT)),
        ),
        listOf(
            TaggedItem("苹果 1 个"),
            TaggedItem("花生酱 10g", setOf(DietRestriction.PEANUT)),
        ),
        listOf(
            TaggedItem("水煮蛋 1 个"),
            TaggedItem("小番茄 100g"),
        ),
        listOf(
            TaggedItem("蛋白粉 1 勺"),
            TaggedItem("香蕉 1 根"),
        ),
    )

    /** 晚餐模板。 */
    val DINNER: List<List<TaggedItem>> = listOf(
        listOf(
            TaggedItem("三文鱼 120g", setOf(DietRestriction.SEAFOOD)),
            TaggedItem("杂粮饭 100g"),
            TaggedItem("菠菜沙拉 200g"),
        ),
        listOf(
            TaggedItem("鸡胸肉 120g"),
            TaggedItem("红薯 120g"),
            TaggedItem("西兰花 200g"),
        ),
        listOf(
            TaggedItem("虾仁 130g", setOf(DietRestriction.SEAFOOD)),
            TaggedItem("糙米 100g"),
            TaggedItem("凉拌黄瓜 150g"),
        ),
        listOf(
            TaggedItem("瘦牛肉 120g"),
            TaggedItem("玉米 100g"),
            TaggedItem("清炒芦笋 200g"),
        ),
    )

    /** 取某餐次的全部模板（**非空**，保证选取不会越界）。 */
    fun forType(type: MealType): List<List<TaggedItem>> = when (type) {
        MealType.BREAKFAST -> BREAKFAST
        MealType.LUNCH -> LUNCH
        MealType.SNACK -> SNACK
        MealType.DINNER -> DINNER
    }
}
