package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.PlanRepository
import javax.inject.Inject

/**
 * 「恢复为 AI 推荐」用例 —— 交还 AI 接管。
 *
 * 落地为 `is_user_edited = 0`，之后 AI 下次生成即可重新覆盖该行
 * （schema-v2 §6.3 坑 5：给用户一条改错后回头的出口）。
 *
 * @param planId 计划条目 id
 */
class ResetPlanItemUseCase @Inject constructor(
    private val planRepository: PlanRepository,
) {

    suspend operator fun invoke(planId: Long) {
        planRepository.resetToRecommended(planId)
    }
}
