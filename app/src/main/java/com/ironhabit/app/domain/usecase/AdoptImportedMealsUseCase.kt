package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ImportedMealDraft
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.repository.MealRepository
import javax.inject.Inject
import kotlinx.datetime.Clock

/**
 * 把外部 AI 文档里的**餐次草案**写进 `meals`（只写被点的那几天）。
 *
 * ## 为什么单独一个用例，而不是塞进 [GenerateDietPlanUseCase]
 * 那个用例是"本地规则从零排一餐"，这里是"把一份已经算好的草案落到槽位"，
 * 两者唯一的共同点是都走 [MealRepository.upsertGenerated]。合成一个的话，
 * `isUserEdited` 的保护规则会出现两份，而"为什么这一餐没被改"就会有两种答案 ——
 * 训练侧正是靠 [PlanDraftProjector] 收成一份才没出事。
 *
 * ## 三条口径（和训练侧导入逐条对齐）
 * 1. **只写、不删**：走 `upsertGenerated`，命中已有行是 UPDATE 且保留 `isCompleted` / `isActive` /
 *    `createdAt`；整个路径没有任何 `DELETE`（`meal_items.meal_id` 对 `meals` 是
 *    `ON DELETE CASCADE`，删一行等于毁掉用户的真实记录）。
 * 2. **手改过的餐次不动**：那一格计入 [Result.preservedCount]，界面在采纳**之前**就该说清楚。
 * 3. **导入的行算 AI 行**：`upsertGenerated` 会强制 `isUserEdited = false`，
 *    所以之后本地「生成饮食」会把这些餐次原地覆盖掉。预览页必须写明这一点，
 *    否则用户二十分钟的外部对话会静默蒸发。
 */
class AdoptImportedMealsUseCase @Inject constructor(
    private val mealRepository: MealRepository,
    private val clock: Clock,
) {

    /** @param writtenCount 真的写进去几餐；@param preservedCount 因为用户改过而**没动**几餐。 */
    data class Result(val writtenCount: Int = 0, val preservedCount: Int = 0)

    suspend operator fun invoke(
        weekStartEpochDay: Long,
        drafts: List<ImportedMealDraft>,
        days: Set<Int>,
    ): Result {
        val wanted: List<ImportedMealDraft> = drafts.filter { draft -> draft.dayOfWeek in days }
        if (wanted.isEmpty()) return Result()

        var written = 0
        var preserved = 0
        // 🔒 逐天读**含软删**的现有行：软删行仍然占着 UNIQUE(date, meal_type) 槽位，
        // 而且可能带着 `isUserEdited = true` —— 只看启用行会把用户删掉的餐「复活」。
        wanted.groupBy { draft -> draft.dayOfWeek }.forEach { (dayOfWeek, forDay) ->
            val epochDay: Long = weekStartEpochDay + dayOfWeek - 1L
            val blocked: Set<MealType> = mealRepository.getMealsIncludingInactive(epochDay)
                .filter { meal -> meal.isUserEdited }
                .map { meal -> meal.mealType }
                .toSet()

            val writable: List<Meal> = forDay.filter { draft -> draft.mealType !in blocked }
                .map { draft -> draft.toMeal(epochDay) }
            val skipped: Int = forDay.size - writable.size
            if (skipped > 0) preserved += skipped
            if (writable.isEmpty()) return@forEach
            written += mealRepository.upsertGenerated(writable)
        }
        return Result(writtenCount = written, preservedCount = preserved)
    }

    /**
     * 草案 → 一餐。
     *
     * `items_text` 里每行一条食物、**只写克数**：食物库那 24 种份量单位里是
     * `份（干）`、`碗（生）`、`把（生）` 这种东西，换成"1.3 碗"要么骗人要么骗自己。
     * 数字用 [ImportedMealDraft.kcal] —— 那是解析时按每 100g 算好的，这里不再算第二遍。
     */
    private fun ImportedMealDraft.toMeal(epochDay: Long): Meal = Meal(
        dateEpochDay = epochDay,
        mealType = mealType,
        items = entries.map { entry -> "${entry.name} ${entry.grams}g" },
        kcal = kcal,
        proteinG = proteinG,
        sortOrder = mealType.ordinal,
        createdAt = clock.now().toEpochMilliseconds(),
    )
}
