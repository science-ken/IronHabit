package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * [ToggleHabitUseCase] 单测：验证对 [HabitRepository.setLog] 的精确转发。
 *
 * 用例本身不含日期计算（由仓库内部自算 `dateStartMillis`），因此这里只断言
 * 「调用参数透传正确」，覆盖完成 / 取消两种分支。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToggleHabitUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val habitRepository = mockk<HabitRepository>(relaxed = true)

    private val useCase = ToggleHabitUseCase(habitRepository = habitRepository)

    @Test
    fun markDoneForwardsExactArguments() = runTest {
        coEvery {
            habitRepository.setLog(any(), any(), any(), any())
        } returns Unit

        useCase(habitId = 42L, epochDay = 20_500L, done = true)

        coVerify(exactly = 1) {
            habitRepository.setLog(
                habitId = 42L,
                epochDay = 20_500L,
                done = true,
                note = null,
            )
        }
    }

    @Test
    fun markUndoneForwardsFalseFlag() = runTest {
        coEvery {
            habitRepository.setLog(any(), any(), any(), any())
        } returns Unit

        useCase(habitId = 7L, epochDay = 20_498L, done = false)

        coVerify(exactly = 1) {
            habitRepository.setLog(
                habitId = 7L,
                epochDay = 20_498L,
                done = false,
                note = null,
            )
        }
    }
}
