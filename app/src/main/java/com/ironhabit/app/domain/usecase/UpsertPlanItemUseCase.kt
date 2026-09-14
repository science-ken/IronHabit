package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.PlanRepository
import javax.inject.Inject

/**
 * 「新增 / 修改计划条目」用例。
 *
 * **显式 upsert**（命中 `UPDATE` / 未命中 `INSERT`，**禁用 `REPLACE`**）并置
 * `isUserEdited = true`，避免 AI 下次生成时静默撤销用户改动（schema-v2 §6.3 坑 4/6）。
 *
 * @return 落库后的行 id
 */
class UpsertPlanItemUseCase @Inject constructor(
    private val planRepository: PlanRepository,
) {

    suspend operator fun invoke(plan: WeekPlan): Long = planRepository.upsert(plan)
}
