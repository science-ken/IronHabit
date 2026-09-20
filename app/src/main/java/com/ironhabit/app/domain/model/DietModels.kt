package com.ironhabit.app.domain.model

/**
 * 每日营养目标（**不落库**，每次由规则现算，见 `docs/schema-v3-meals.md` §7.2 / §10-4）。
 *
 * 代价：历史某天的目标会随当前体重变化而变化；若将来要「历史目标」，再加表。
 *
 * @property targetKcal 目标热量（kcal，已钳制到 `[1200, 4000]`）
 * @property targetProtein 目标蛋白质（g，已钳制到 `[50, 300]`）
 * @property usedDefaults 是否使用了「默认值补全」（档案未填全 / 无体重记录）→ UI 显示非阻断提示
 */
data class DietTarget(
    val targetKcal: Int = 0,
    val targetProtein: Int = 0,
    val usedDefaults: Boolean = false,
)

/**
 * 当日饮食合计。
 *
 * ⚠️ **两个不同的聚合口径**（对应预览 `kcal(done)` / `kcal(all)`，见 F4）：
 * - `intake*`：**只算已完成的餐**（`is_completed = 1`）；
 * - `plan*`：**全部餐**（计划总量）。
 *
 * 空集时 `SUM()` 返回 `NULL` —— DAO 侧以 `COALESCE(..., 0)` 兜底，避免「新的一天一进页面就崩」。
 *
 * @property intakeKcal 已摄入热量（只算已完成餐）
 * @property intakeProtein 已摄入蛋白质（只算已完成餐）
 * @property planKcal 计划总热量（全部餐）
 * @property planProtein 计划总蛋白质（全部餐）
 */
data class MealTotals(
    val intakeKcal: Int = 0,
    val intakeProtein: Double = 0.0,
    val planKcal: Int = 0,
    val planProtein: Double = 0.0,
)

/**
 * 「今日饮食」聚合视图（餐列表 + 合计 + 已记条目 + 实际摄入 + 目标）。
 *
 * @property meals 当日启用餐列表（含未完成），按 `sortOrder` 升序
 * @property totals 当日合计（已摄入 / 计划）—— 走的是 `meals` 整餐列的 SQL 聚合
 * @property items 当日**已记下的条目**（`meal_items`）。有行才代表"真的吃了"。
 * @property intake 实际摄入：明细优先 → 打勾的整餐值 → 0。见 [MealIntakeCalculator]。
 * @property target 当日营养目标（规则现算）
 *
 * ⚠️ **别拿 [totals] 的 `intakeKcal` 当"吃了多少"**：它统计的是"打了勾的那一餐的整餐值"，
 * 而打了勾可能是粗记、也可能一口都没记（勾是计划完成的标记）。
 * 真实摄入只看 [intake]。
 */
data class TodayMeals(
    val meals: List<Meal> = emptyList(),
    val totals: MealTotals = MealTotals(),
    val items: List<MealItem> = emptyList(),
    val intake: MealIntake = MealIntake(0, 0.0, 0.0, 0.0, 0, emptySet()),
    val target: DietTarget = DietTarget(),
)
