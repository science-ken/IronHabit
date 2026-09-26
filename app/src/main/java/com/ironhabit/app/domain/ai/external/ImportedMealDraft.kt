package com.ironhabit.app.domain.ai.external

import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.FoodNutrition
import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.model.MealType

/**
 * 一份文档是从**哪个入口**粘进来的。两个入口两份模板（2026-09-26 拆），
 * 各自的合同只认自己那一半 —— 粘错入口当场 `WRONG_SCHEMA`，而不是"读通了但少一半"。
 */
enum class ImportSection {
    /** 「导入训练」：读 `days` / `profile` / `newExercises`。 */
    TRAINING,

    /** 「导入饮食」：只读 `meals` / `newFoods`。档案改动仍然只属于训练那一份。 */
    DIET,
    ;

    /** 本入口认的回程合同。 */
    val schema: String
        get() = when (this) {
            TRAINING -> ExternalPlanSchema.SCHEMA
            DIET -> ExternalPlanSchema.DIET_SCHEMA
        }
}

/**
 * 目标周**现在**某一餐是什么样：预览页那两句"会不会动到你的东西"的判据。
 *
 * 它来自库里已有的行，不是草案 —— 所以必须和草案一起送到预览页，
 * 否则界面只能说"排了 4 餐"，说不出"其中午餐你手改过，这次不会动它"。
 *
 * @property isUserEdited 用户改过 / 删过 → [com.ironhabit.app.domain.usecase.AdoptImportedMealsUseCase] 会跳过这一格
 * @property isActive `false` = 用户把这餐标成了「不吃」（软删行仍占槽位）。
 *   它一定同时 `isUserEdited == true`，但界面要说的是另一句话：那一格**写进去也看不见**。
 * @property isCompleted 已经勾了"吃了这餐" → 改这餐的**计划热量**会连带改本周的平均摄入数字
 */
data class MealSlotSnapshot(
    val dayOfWeek: Int,
    val mealType: MealType,
    val isUserEdited: Boolean,
    val isCompleted: Boolean,
    val isActive: Boolean = true,
)

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
 *
 * ## 这一条是**预填资料**，不是既成事实
 * [com.ironhabit.app.ui.screens.ai.ImportPlanSheet] 把这四个数摊在建库表单上，
 * 用户改过、勾过才落库（红线：模型给的营养数字一律不当结论，只当预填）。
 */
data class ImportedNewFood(
    val name: String,
    val kcalPer100g: Int? = null,
    val proteinPer100g: Double? = null,
    val carbsPer100g: Double? = null,
    val fatPer100g: Double? = null,
    /** 建库时一起写进去的忌口标签。默认空 = 文档没给、用户也没勾。 */
    val dietaryTags: Set<DietRestriction> = emptySet(),
) {
    /** 数值齐了才能建库（缺任何一项都得让用户补，见 [ImportedNewFood] 的说明）。 */
    val hasCompleteNutrition: Boolean
        get() = kcalPer100g != null && proteinPer100g != null && carbsPer100g != null && fatPer100g != null
}

/**
 * 导入弹层里**建库表单的一行**：文档预填 + 用户当场改的 + 勾没勾。
 *
 * ## 为什么数值存字符串而不是 `Double?`
 * 这是一张正在被编辑的表。`""`（还没填）、`"1."`（正在打）与 `0.0`（一个陈述）
 * 在 `Double?` 里塌成两种，界面就没法区分"用户清了那一格"和"这格本来就是 0"。
 * 解析与校验一律走 [InputLimits]（与食物库表单同一口径）。
 *
 * ## 为什么默认勾上，而动作侧默认不勾
 * 是用户自己说"库里没有的也要能加进来"才走到这一步的，这一餐缺这条就是缺一条。
 * 但**数值没填齐就不给勾** —— 那样等于替用户签一个"这份食物 0 kcal/100g"。
 *
 * @property checked 只有 [canBuild] 为真时这个勾选才算数（界面把勾选框置灰挡住不完整的行）
 */
data class NewFoodCandidate(
    val name: String,
    val kcal: String,
    val protein: String,
    val carbs: String,
    val fat: String,
    val dietaryTags: Set<DietRestriction> = emptySet(),
    val checked: Boolean,
    /** 「改」展开没有：整张表默认只占一行，一餐常有六七条陌生食物，全展开会把按钮顶出屏幕。 */
    val isEditing: Boolean = false,
) {

    /** 名字非空，且四项数值都填了**且都在可记录区间内**（空白 ≠ 0）。 */
    val canBuild: Boolean
        get() = name.isNotBlank() &&
            kcal.trim().toIntOrNull()?.let { InputLimits.isValidFoodKcalPer100G(it) } == true &&
            listOf(protein, carbs, fat).all { text ->
                text.trim().toDoubleOrNull()?.let { InputLimits.isValidFoodMacroPer100G(it) } == true
            }

    /** 勾了、也填齐了才建 —— 两个条件少任何一个都返回 `null`（调用方直接跳过这一行）。 */
    fun toNewFood(): ImportedNewFood? {
        if (!checked || !canBuild) return null
        return ImportedNewFood(
            name = name.trim(),
            kcalPer100g = kcal.trim().toInt(),
            proteinPer100g = protein.trim().toDouble(),
            carbsPer100g = carbs.trim().toDouble(),
            fatPer100g = fat.trim().toDouble(),
            dietaryTags = dietaryTags,
        )
    }
}

/** 文档预填 → 表单初始那一行。数值齐 = 默认勾上；缺任何一项 = 不勾，等用户补。 */
fun ImportedNewFood.toCandidate(): NewFoodCandidate = NewFoodCandidate(
    name = name,
    kcal = kcalPer100g?.toString().orEmpty(),
    protein = proteinPer100g?.toString().orEmpty(),
    carbs = carbsPer100g?.toString().orEmpty(),
    fat = fatPer100g?.toString().orEmpty(),
    dietaryTags = dietaryTags,
    checked = hasCompleteNutrition,
)
