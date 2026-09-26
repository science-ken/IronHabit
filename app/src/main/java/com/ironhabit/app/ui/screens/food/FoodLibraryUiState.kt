package com.ironhabit.app.ui.screens.food

import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Food
import java.text.Collator
import java.util.Locale

/**
 * 食物库弹层的状态 + 纯筛选逻辑。
 *
 * 筛选写成**顶层纯函数**而不是 ViewModel 里的私有方法，与 `TrainUiState.searchExercises`
 * 同一取舍：这样它能被 JVM 单测直接喂列表断言，不必起 ViewModel、也不必 mock 仓库。
 */
data class FoodLibraryUiState(
    /** 启用中的食物（已按拼音序排好）—— 就是"库里"那一栏。 */
    val allFoods: List<Food> = emptyList(),
    /** 已停用的食物（同样按拼音序）。停用是软删，行还在库里，所以这里必须看得见。 */
    val inactiveFoods: List<Food> = emptyList(),
    /** 搜索框原文。 */
    val query: String = "",
    /** 「已停用」筛选是否展开。 */
    val showInactive: Boolean = false,
    /** 档案里登记的忌口。空集合 = 这一整块不参与筛选（不是"全挡"）。 */
    val dietaryAvoid: Set<DietRestriction> = emptySet(),
    /** 「显示被忌口挡掉的」是否展开。默认收起：挡掉的东西堆在列表里会挤掉真正能挑的。 */
    val showRestricted: Boolean = false,
    /** 正在新建/编辑的食物 id；`null` = 列表态。 */
    val editingFoodId: Long? = null,
) {
    /** 当前显示给用户的启用条目。挑选模式只用这一条（见 [visibleInactiveFoods]）。 */
    val visibleFoods: List<Food> get() = searchFoods(allFoods.filterNot { isBlockedByAvoid(it, dietaryAvoid) }, query)

    /**
     * 命中忌口、被沉到底部那一栏的食物。
     *
     * 只在 [showRestricted] 打开时才非空 —— 与 [visibleInactiveFoods] 同一个闸门写法，
     * 免得每个渲染处都要自己记得判一次。
     */
    val visibleRestrictedFoods: List<Food>
        get() = if (showRestricted) searchFoods(
            allFoods.filter { isBlockedByAvoid(it, dietaryAvoid) },
            query,
        ) else emptyList()

    /**
     * 展开「已停用」后该显示的停用行；收起态恒为空 —— 闸门放在状态里，
     * 免得每个渲染处都要自己记得判 [showInactive]。
     *
     * 挑选模式**永远不渲染它**：`AddMealItemUseCase` 会挡掉停用食物，
     * 摆一个点下去必定失败的按钮更糟。
     */
    val visibleInactiveFoods: List<Food>
        get() = if (showInactive) searchFoods(inactiveFoods, query) else emptyList()

    /** 忌口挡掉了多少条（跟着搜索词走，chip 上的数字必须和展开后看到的对得上）。 */
    val restrictedCount: Int get() = allFoods.count { isBlockedByAvoid(it, dietaryAvoid) && it.name.contains(query.trim(), ignoreCase = true) }

    /** 三段都没有命中时才该显示空态提示。 */
    val nothingToShow: Boolean
        get() = visibleFoods.isEmpty() && visibleInactiveFoods.isEmpty() && visibleRestrictedFoods.isEmpty()
}

/**
 * 按名称做包含匹配（Q13/Q23：**本期只做这一种**，拼音字段是后续刀，不引第三方依赖）。
 *
 * 空查询 = 原样返回（不是返回空列表 —— 打开弹层就该看到整库）。
 * 匹配忽略大小写与首尾空白，因为"鸡蛋"和" 鸡蛋 "必须是同一个结果。
 */
fun searchFoods(foods: List<Food>, query: String): List<Food> {
    val trimmed: String = query.trim()
    if (trimmed.isEmpty()) return foods
    return foods.filter { food -> food.name.contains(trimmed, ignoreCase = true) }
}

/**
 * 这条食物是否命中了用户的忌口。
 *
 * ⚠️ 它**只是筛选**，不是安全保证：库里只有少数条目带标签（内置 127 条里约三成），
 * 用户自建的食物更是一条都没有（表单不允许编辑标签）。所以界面上不能写「已按忌口过滤」，
 * 只能写"挡掉了库里标注过的这几类"，并补一句没标注的不代表安全。
 */
fun isBlockedByAvoid(food: Food, avoid: Set<DietRestriction>): Boolean =
    avoid.isNotEmpty() && food.dietaryTags.any { tag -> tag in avoid }

/**
 * 中文按拼音排序。
 *
 * 为什么不在 SQL 里 `ORDER BY name`：SQLite 默认 BINARY 排序，中文实际是按 UTF-8 码位排，
 * 「苹果」不会排在「香蕉」前面。`Collator(Locale.CHINA)` 是训练页肌群筛选已经在用的做法，
 * 沿用而不是另发明一套。
 */
fun sortByPinyin(foods: List<Food>): List<Food> {
    val collator = Collator.getInstance(Locale.CHINA)
    return foods.sortedWith(compareBy(collator) { food -> food.name })
}
