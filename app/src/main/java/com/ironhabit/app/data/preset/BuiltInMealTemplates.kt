package com.ironhabit.app.data.preset

import com.ironhabit.app.domain.model.MealType

/**
 * 内置餐次模板常量（**纯数据**，按 [MealType] 分组）。
 *
 * 每套模板 = 一组食物条目文本；规则引擎用**确定性伪随机**
 * `((epochDay + mealType.ordinal) % templates.size)` 选取（见 `DietPlanGenerator`），
 * 保证「同一天同一餐永远算出同一条」→ 可复现、可单测。
 *
 * ⚠️ **这些条目属"预置数据"**（与 `BuiltInExercises` 的预置动作名同类，架构 §7.5 允许写在
 * Kotlin 中的唯一例外）——它们是**内容数据**，不是 UI 文案；而**餐次名**（早餐/午餐/加餐/晚餐）
 * 属 UI 文案，一律走 `strings.xml`（`meal_breakfast` 等），绝不硬编码。
 *
 * 诚实登记：模板**不带忌口标签**，故本期**不按 `dietaryAvoid` 过滤条目**
 * （设计文档 §7.2 的规则里没有排除机制；§7.5.2 提到忌口是"排除项"但未定义数据结构 → 见汇报）。
 */
object BuiltInMealTemplates {

    /** 早餐模板。 */
    val BREAKFAST: List<List<String>> = listOf(
        listOf("燕麦 50g + 脱脂牛奶 250ml", "水煮蛋 2 个", "蓝莓 80g"),
        listOf("全麦吐司 2 片", "煎蛋 2 个", "牛油果 1/2 个"),
        listOf("无糖酸奶 200g", "香蕉 1 根", "核桃 10g"),
        listOf("杂粮粥 1 碗", "水煮蛋 1 个", "小番茄 100g"),
    )

    /** 午餐模板。 */
    val LUNCH: List<List<String>> = listOf(
        listOf("糙米饭 150g", "鸡胸肉 150g", "西兰花 200g（少油）"),
        listOf("藜麦 120g", "清蒸鱼 150g", "凉拌菠菜 200g"),
        listOf("全麦意面 100g", "瘦牛肉 120g", "混合蔬菜 200g"),
        listOf("紫薯 150g", "去皮鸡腿肉 130g", "清炒时蔬 200g"),
    )

    /** 加餐模板。 */
    val SNACK: List<List<String>> = listOf(
        listOf("无糖希腊酸奶 150g", "杏仁 15g"),
        listOf("苹果 1 个", "花生酱 10g"),
        listOf("水煮蛋 1 个", "小番茄 100g"),
        listOf("蛋白粉 1 勺", "香蕉 1 根"),
    )

    /** 晚餐模板。 */
    val DINNER: List<List<String>> = listOf(
        listOf("三文鱼 120g", "杂粮饭 100g", "菠菜沙拉 200g"),
        listOf("鸡胸肉 120g", "红薯 120g", "西兰花 200g"),
        listOf("虾仁 130g", "糙米 100g", "凉拌黄瓜 150g"),
        listOf("瘦牛肉 120g", "玉米 100g", "清炒芦笋 200g"),
    )

    /** 取某餐次的全部模板（**非空**，保证选取不会越界）。 */
    fun forType(type: MealType): List<List<String>> = when (type) {
        MealType.BREAKFAST -> BREAKFAST
        MealType.LUNCH -> LUNCH
        MealType.SNACK -> SNACK
        MealType.DINNER -> DINNER
    }
}
