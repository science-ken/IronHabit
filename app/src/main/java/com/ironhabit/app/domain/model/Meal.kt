package com.ironhabit.app.domain.model

/**
 * 餐次类型。
 *
 * ⚠️ **ordinal 必须按 F3 顺序**（早餐 → 午餐 → 加餐 → 晚餐，加餐在午/晚之间）。
 * 该顺序同时决定：
 * - 规则引擎的「模板确定性选取」(`(dateEpochDay + ordinal) % templates.size`)；
 * - 默认 `sortOrder`。
 *
 * 改动枚举顺序会让**已生成的饮食内容错位**，故顺序是**接口的一部分**。
 */
enum class MealType {
    /** 早餐 */
    BREAKFAST,

    /** 午餐 */
    LUNCH,

    /** 加餐（午/晚之间） */
    SNACK,

    /** 晚餐 */
    DINNER,
}

/**
 * 领域模型：一餐（某天某一餐，**每日实例**，不是周模板）。
 *
 * @property id 主键，自增；`0` 表示尚未落库
 * @property dateEpochDay 日期口径 = `LocalDate.toEpochDays()`
 * @property mealType 餐次
 * @property items 食物条目（多条；与 `items_text` 用 `\n` 互转，见 `MealMapper`）
 * @property kcal 整餐热量
 * @property proteinG 整餐蛋白质（克）
 * @property isCompleted 是否已完成（对应预览 `done`）
 * @property sortOrder 同日内排序
 * @property isActive 是否启用（`false` = 用户「不吃这餐」的软删除行）
 * @property isUserEdited 用户改过 / 删过 → 重新生成时整行跳过
 * @property createdAt 创建时间戳（UTC 毫秒）
 */
data class Meal(
    val id: Long = 0L,
    val dateEpochDay: Long = 0L,
    val mealType: MealType = MealType.BREAKFAST,
    val items: List<String> = emptyList(),
    val kcal: Int = 0,
    val proteinG: Double = 0.0,
    val isCompleted: Boolean = false,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val isUserEdited: Boolean = false,
    val createdAt: Long = 0L,
)
