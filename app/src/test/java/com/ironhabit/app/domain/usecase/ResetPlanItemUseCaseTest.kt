package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.PlanRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResetPlanItemUseCase] 单测 —— 补的是 v6 定稿里"顺带定下的 3 条"之一：
 * **「恢复为推荐」必须带 `is_active = 1` 守卫**。
 *
 * 为什么这件事重要：软删除行（`is_active = 0`）是"用户明确删掉的槽位"。
 * 如果对它执行"恢复为推荐"（把 `is_user_edited` 清成 0），这个槽位就**失去了用户手改保护**，
 * 下一次 AI 生成会往里写 → 用户删掉的那条**复活**，而用户完全不知道是自己哪一步点出来的。
 */
class ResetPlanItemUseCaseTest {

    private val planRepository = mockk<PlanRepository>(relaxed = true)

    private fun useCase(): ResetPlanItemUseCase = ResetPlanItemUseCase(planRepository = planRepository)

    private fun stubRows(rows: List<WeekPlan>) {
        every { planRepository.observeAllIncludingInactive() } returns flowOf(rows)
    }

    @Test
    fun activeRow_isResetToRecommended() = runTest {
        stubRows(listOf(WeekPlan(id = 7L, exerciseId = 1L, dayOfWeek = 1, isUserEdited = true)))

        val changed: Boolean = useCase()(7L)

        assertTrue("正常行：应把 is_user_edited 清掉、交还 AI 接管", changed)
        coVerify(exactly = 1) { planRepository.resetToRecommended(7L) }
    }

    @Test
    fun softDeletedRow_isNeverTouched() = runTest {
        // 用户删掉的那条：is_active = false + is_user_edited = true
        stubRows(
            listOf(
                WeekPlan(
                    id = 11L,
                    exerciseId = 2L,
                    dayOfWeek = 3,
                    isActive = false,
                    isUserEdited = true,
                ),
            ),
        )

        val changed: Boolean = useCase()(11L)

        assertFalse("软删行：必须什么都不做（否则它会被下次生成复活）", changed)
        coVerify(exactly = 0) { planRepository.resetToRecommended(any()) }
    }

    @Test
    fun unknownId_doesNothing() = runTest {
        stubRows(listOf(WeekPlan(id = 1L, exerciseId = 1L, dayOfWeek = 1)))

        val changed: Boolean = useCase()(999L)

        assertFalse("不存在的 id：不写库、返回 false", changed)
        coVerify(exactly = 0) { planRepository.resetToRecommended(any()) }
    }

    @Test
    fun onlyTheTargetRowIsAffected() = runTest {
        stubRows(
            listOf(
                WeekPlan(id = 1L, exerciseId = 1L, dayOfWeek = 1, isUserEdited = true),
                WeekPlan(id = 2L, exerciseId = 2L, dayOfWeek = 1, isActive = false, isUserEdited = true),
                WeekPlan(id = 3L, exerciseId = 3L, dayOfWeek = 3, isUserEdited = true),
            ),
        )

        assertTrue(useCase()(3L))
        coVerify(exactly = 1) { planRepository.resetToRecommended(3L) }
        coVerify(exactly = 0) { planRepository.resetToRecommended(1L) }
        coVerify(exactly = 0) { planRepository.resetToRecommended(2L) }
    }

    @Test
    fun repositoryFailure_propagates_andDoesNotSwallow() = runTest {
        stubRows(listOf(WeekPlan(id = 5L, exerciseId = 1L, dayOfWeek = 1)))
        coEvery { planRepository.resetToRecommended(5L) } throws IllegalStateException("db down")

        val thrown = runCatching { useCase()(5L) }.exceptionOrNull()

        assertTrue(
            "写库失败必须往外抛：界面要能显示错误，而不是「点了没反应」",
            thrown is IllegalStateException,
        )
    }
}
