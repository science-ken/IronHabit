package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.PlanRepository
import javax.inject.Inject

/**
 * 「删除计划条目」用例 —— **软删除**。
 *
 * 落地为 `is_active = 0` **且** `is_user_edited = 1`：既保留 `UNIQUE(day_of_week, exercise_id)`
 * 的唯一槽位，又阻止 AI 下次生成时把该行**静默复活**（schema-v2 §6.3 坑 3，最严重的一个坑）。
 * **禁用物理 `DELETE`**；历史打卡记录不受影响。
 *
 * @param planId 计划条目 id
 */
class RemovePlanItemUseCase @Inject constructor(
    private val planRepository: PlanRepository,
) {

    suspend operator fun invoke(planId: Long) {
        planRepository.delete(planId)
    }
}
