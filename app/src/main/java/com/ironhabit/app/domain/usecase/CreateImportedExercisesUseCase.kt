package com.ironhabit.app.domain.usecase

import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.ai.external.ImportedNewExercise
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseSource
import com.ironhabit.app.domain.repository.ExerciseRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * 把用户**勾选确认过的**新动作建进动作库。
 *
 * ## 为什么这一步要用户点一下
 * 建动作是往库里**永久加一行**：名字漂一个空格就多出一个"杠铃深蹲 "，分类/肌群瞎填会让以后的
 * 伤病避让静默失效。所以文档只能"申请"，用户勾选才是"同意"。
 *
 * ## 幂等：库里已有同名（**含已停用**）一律跳过
 * 与 `SuggestExercisesUseCase` 同一条口径 —— 停用不等于没有，重建等于把用户主动停掉的东西
 * 悄悄放回可用池。判据用内存比对而不是靠 `exercises.name` 的 UNIQUE 抛异常：
 * 撞约束只能表达"失败了"，表达不了"跳过了几条、为什么跳"。
 *
 * ## 来源标 [ExerciseSource.AI_SUGGESTED]
 * 不是 `CUSTOM`：用户一旦在动作页改过它，产品规则会把它单向降级成 `CUSTOM`（那正是
 * [com.ironhabit.app.domain.model.ExerciseSource] 立的三态语义），这里替不了他改。
 */
class CreateImportedExercisesUseCase @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** @return 真的新建出来的条数（被幂等跳过的不计） */
    suspend operator fun invoke(candidates: List<ImportedNewExercise>): Int =
        withContext(ioDispatcher) {
            if (candidates.isEmpty()) return@withContext 0

            // 含停用：`observeActive()` 会漏掉用户停掉的那些，那样就会把停掉的"复活"成第二条。
            val existingNames: Set<String> = exerciseRepository.getAll()
                .map { exercise -> exercise.name.trim() }
                .toSet()

            var created = 0
            for (candidate: ImportedNewExercise in candidates) {
                if (candidate.name in existingNames) continue

                exerciseRepository.upsert(
                    Exercise(
                        id = 0L,   // 0 = 尚未落库，DAO 走自增插入
                        name = candidate.name,
                        category = candidate.category,
                        source = ExerciseSource.AI_SUGGESTED,
                        muscleGroups = candidate.muscleGroups,
                        equipment = candidate.equipment.toList(),
                        isActive = true,
                        // 默认组次沿用表单口径（3 × 12）：这不是"AI 建议的强度"，只是新行不能空着；
                        // 真正的组数次数在计划行上，用户随时可改。
                        defaultSets = DEFAULT_SETS,
                        defaultReps = DEFAULT_REPS,
                        defaultDurationSec = null,
                        createdAt = clock.now().toEpochMilliseconds(),
                    ),
                )
                created++
            }
            created
        }

    private companion object {
        const val DEFAULT_SETS: Int = 3
        const val DEFAULT_REPS: Int = 12
    }
}
