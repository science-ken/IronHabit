package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.PlanRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * 「恢复为 AI 推荐」用例 —— 交还 AI 接管。
 *
 * 落地为 `is_user_edited = 0`，之后 AI 下次生成即可重新覆盖该行
 * （schema-v2 §6.3 坑 5：给用户一条改错后回头的出口）。
 *
 * ## 🔒 守卫（v6 定稿「顺带定下的 3 条」之一）
 * **软删除行（`isActive = false`）一律不动** —— 那是"用户明确删掉的槽位"，
 * 清掉它的 `is_user_edited` 等于拆掉用户手改保护，下次生成就会把它**复活**。
 * 守卫两道：这里先判（可单测），DAO 的 SQL 再兜一次（防别的调用方绕过）。
 *
 * 实现说明：仓库层没有"按 id 取单行"的方法，这里用全量流筛一条 —— 表很小
 * （最多 `7 天 × 每日动作数` 量级），不值得为它扩接口。
 */
class ResetPlanItemUseCase @Inject constructor(
    private val planRepository: PlanRepository,
) {

    /**
     * @param planId 计划条目 id
     * @return `true` = 已交还 AI 接管；`false` = **什么都没做**（id 不存在，或该行是软删除行）
     */
    suspend operator fun invoke(planId: Long): Boolean {
        val row: WeekPlan = planRepository.observeAllIncludingInactive().first()
            .firstOrNull { plan -> plan.id == planId }
            ?: return false
        if (!row.isActive) return false

        planRepository.resetToRecommended(planId)
        return true
    }
}
