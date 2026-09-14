package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseSource
import com.ironhabit.app.domain.repository.ExerciseRepository
import javax.inject.Inject

/**
 * 编辑动作输入（不含 `source` —— 由用例强制置为 [ExerciseSource.CUSTOM]）。
 *
 * @property id 动作 id；`0` 表示新增
 * @property name 动作名
 * @property category 分类
 * @property muscleGroups 有序肌群列表，第一个 = 主肌群
 * @property note 动作要点备注，可空
 * @property defaultSets 默认组数
 * @property defaultReps 默认每组次数
 * @property defaultDurationSec 默认时长（秒），可空
 * @property sortOrder 展示排序
 * @property timesUsed 累计打卡次数
 * @property isActive 是否启用
 * @property createdAt 创建时间戳（UTC 毫秒）
 */
data class UpdateExerciseInput(
    val id: Long = 0L,
    val name: String,
    val category: ExerciseCategory = ExerciseCategory.CUSTOM,
    val muscleGroups: List<String> = emptyList(),
    val note: String? = null,
    val defaultSets: Int? = null,
    val defaultReps: Int? = null,
    val defaultDurationSec: Int? = null,
    val sortOrder: Int = 0,
    val timesUsed: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long = 0L,
)

/**
 * 「编辑动作」用例。
 *
 * **产品规则（对用户承诺，不得丢失）**：任何用户编辑动作（改名 / 改备注 / 改默认组次 / 改肌群）
 * → `source` **强制降级为 [ExerciseSource.CUSTOM]**，且**单向不可逆**
 * （内置 / AI 推荐被编辑同样降级，见 schema-v2 §3.2 / §10.2）。
 *
 * @return 落库后的行 id
 */
class UpdateExerciseUseCase @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
) {

    suspend operator fun invoke(input: UpdateExerciseInput): Long {
        val exercise = Exercise(
            id = input.id,
            name = input.name,
            category = input.category,
            // 产品规则：用户编辑 → source 单向降级为 CUSTOM。
            source = ExerciseSource.CUSTOM,
            muscleGroups = input.muscleGroups,
            note = input.note,
            isActive = input.isActive,
            defaultSets = input.defaultSets,
            defaultReps = input.defaultReps,
            defaultDurationSec = input.defaultDurationSec,
            sortOrder = input.sortOrder,
            timesUsed = input.timesUsed,
            createdAt = input.createdAt,
        )
        return exerciseRepository.upsert(exercise)
    }
}
