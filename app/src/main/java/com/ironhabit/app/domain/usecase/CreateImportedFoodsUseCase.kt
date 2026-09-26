package com.ironhabit.app.domain.usecase

import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.ai.external.ImportedNewFood
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodSource
import com.ironhabit.app.domain.repository.FoodRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * 把用户**在导入弹层里勾了、也确认过数值**的陌生食物建进食物库。
 *
 * ## 为什么这一步一定要人过一遍（红线）
 * 文档给的 four 项成分只是**预填**。模型写"紫薯 120 kcal/100g"不构成建库凭证 ——
 * 一餐的 kcal / 蛋白永远由这张表里的每 100g × 克数现算，所以这一行的数值就是那餐的数字本身。
 * 由模型代签等于把「数字本地算」从推导一路放到成分表。
 *
 * ## 幂等：库里已有同名（**含已停用**）一律跳过
 * 与 [CreateImportedExercisesUseCase] 同一条口径，而这里不只是防重复：
 * `foods.name` 上有 UNIQUE 索引，撞上去是**抛异常**（`ABORT`），一次失败会让整批建库中断。
 * 停用行也不能"复活"——那是用户主动把它移出可用池的。
 *
 * ## 来源标 [FoodSource.AI_SUGGESTED]，不是 `CUSTOM`
 * 这两者的区别是**可信度**：`CUSTOM` 是用户自己抄的数据，`AI_SUGGESTED` 是"模型估的、
 * 用户当场点了头"。食物库卡片靠这一块显示「外部 AI 估」，让用户知道哪几条没有第二份来源可核对。
 */
class CreateImportedFoodsUseCase @Inject constructor(
    private val foodRepository: FoodRepository,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * @param candidates 用户在表单里勾上的那几条（数值已由界面校验过）
     * @return 真的新建出来的条数（重名、数值不齐而跳过的都不计）
     */
    suspend operator fun invoke(candidates: List<ImportedNewFood>): Int =
        withContext(ioDispatcher) {
            if (candidates.isEmpty()) return@withContext 0

            // 含停用行：`observeActive()` 会漏掉用户停掉的那些，那样就把停掉的食物悄悄放回可用池。
            val takenNames: MutableSet<String> = foodRepository.observeAll().first()
                .map { food -> food.name.trim() }
                .toMutableSet()

            var created = 0
            for (candidate: ImportedNewFood in candidates) {
                val name: String = candidate.name.trim()
                // 数值缺一格就不建：空白在营养数据里是"还不知道"，写成 0 会让那一餐的合计系统性偏小。
                val kcal: Int = candidate.kcalPer100g ?: continue
                val protein: Double = candidate.proteinPer100g ?: continue
                val carbs: Double = candidate.carbsPer100g ?: continue
                val fat: Double = candidate.fatPer100g ?: continue
                // 重名（库里的 + 这一批里前面刚建的）都跳过：UNIQUE 撞上去是抛，不是"没建这条"。
                if (name.isEmpty() || !takenNames.add(name)) continue

                foodRepository.upsert(
                    Food(
                        id = 0L,   // 0 = 尚未落库，DAO 走自增插入
                        name = name,
                        kcalPer100g = kcal,
                        proteinPer100g = protein,
                        carbsPer100g = carbs,
                        fatPer100g = fat,
                        // 份量一律留空：`food_servings` 允许空，而"模型说一碗 = 200g"正是最不可信的一句。
                        // 用户要按份记，去食物库自己补 —— 那是他自己家的碗。
                        servings = emptyList(),
                        // 忌口标签在建库表单上就能勾：AI 建进来的恰恰最可能是虾/花生/含麸质的东西，
                        // 不开放的话 R5 那句"忌口会挡住"对新条目等于零。
                        dietaryTags = candidate.dietaryTags,
                        source = FoodSource.AI_SUGGESTED,
                        isActive = true,
                        isUserEdited = false,
                        createdAt = clock.now().toEpochMilliseconds(),
                    ),
                )
                created++
            }
            created
        }
}
