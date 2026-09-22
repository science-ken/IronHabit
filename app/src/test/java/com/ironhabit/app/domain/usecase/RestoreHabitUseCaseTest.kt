package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.ReminderScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [RestoreHabitUseCase] —— [DeleteHabitUseCase] 的反向半边。
 *
 * 删除那里刻意撤了闹钟（见 `DeleteHabitUseCaseTest`），所以恢复这里必须把它排回去：
 * 只翻 `is_active` 的话，习惯回到了列表上、提醒却永远不再响，而 `rescheduleAll`
 * 只在启动 / 开机 / 换时区时才跑 —— 用户看不出任何异常，直到某天又漏练。
 */
class RestoreHabitUseCaseTest {

    @Test
    fun restoringWritesActiveFlagAndReschedulesAlarms() = runTest {
        val repo = mockk<HabitRepository>(relaxed = true)
        val scheduler = mockk<ReminderScheduler>(relaxed = true)
        coEvery { repo.restoreHabit(any()) } returns Unit

        RestoreHabitUseCase(repo, scheduler)(habitId = 7L)

        coVerify { repo.restoreHabit(7L) }
        coVerify(exactly = 1) { scheduler.rescheduleAll() }
    }

    /** 恢复不该顺手删掉任何东西：日志一直在库里，恢复之后连续天数才接得上。 */
    @Test
    fun restoringNeverTouchesLogsOrDeletes() = runTest {
        val repo = mockk<HabitRepository>(relaxed = true)
        val scheduler = mockk<ReminderScheduler>(relaxed = true)

        RestoreHabitUseCase(repo, scheduler)(habitId = 7L)

        coVerify(exactly = 0) { repo.deleteHabit(any()) }
        coVerify(exactly = 0) { scheduler.cancelHabit(any()) }
        coVerify(exactly = 0) { scheduler.cancelAll() }
    }
}
