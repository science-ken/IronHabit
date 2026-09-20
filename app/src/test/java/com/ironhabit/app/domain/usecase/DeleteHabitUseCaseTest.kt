package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.ReminderScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 删习惯必须连它的闹钟一起撤（2.0.9 每习惯一个槽之后才出现的问题）。
 *
 * 软删只是 `is_active = 0`，行还在；而 `rescheduleAll` 只在启动 / 开机 / 换时区时跑。
 * 不在删除这里撤槽，**当天剩下的时间里这条提醒照样会响** —— 用户刚把它删掉。
 */
class DeleteHabitUseCaseTest {

    @Test
    fun deletingAlsoCancelsThatHabitSlot() = runTest {
        val repo = mockk<HabitRepository>(relaxed = true)
        val scheduler = mockk<ReminderScheduler>(relaxed = true)
        coEvery { repo.deleteHabit(any()) } returns Unit

        DeleteHabitUseCase(repo, scheduler)(habitId = 7L)

        coVerify { repo.deleteHabit(7L) }
        coVerify { scheduler.cancelHabit(7L) }
    }

    /** 只能撤被删的那一个，别的习惯还在提醒。 */
    @Test
    fun deletingOneHabit_leavesOtherHabitSlotsAlone() = runTest {
        val repo = mockk<HabitRepository>(relaxed = true)
        val scheduler = mockk<ReminderScheduler>(relaxed = true)

        DeleteHabitUseCase(repo, scheduler)(habitId = 7L)

        coVerify(exactly = 1) { scheduler.cancelHabit(7L) }
        coVerify(exactly = 0) { scheduler.cancelHabit(8L) }
        coVerify(exactly = 0) { scheduler.cancelAll() }
    }
}
