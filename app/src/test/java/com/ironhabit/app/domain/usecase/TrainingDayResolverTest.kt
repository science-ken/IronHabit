package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.test.todayClockFor
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TrainingDayResolver] 的三条规则 + 跨周口径。
 *
 * 这个判据直接决定当天按"训练日"还是"休息日"给热量，所以每条规则都要有断言：
 * 判错一天不是显示问题，是**吃进去的热量**问题。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingDayResolverTest {

    private val utc = TimeZone.UTC
    private val today: Long = LocalDate(2026, 3, 4).toEpochDays().toLong() // 周三

    private val checkInRepository = mockk<CheckInRepository>(relaxed = true)
    private val planRepository = mockk<PlanRepository>(relaxed = true)

    private val clock: Clock = object : Clock {
        // `Instant + Long` 不存在（要 Duration），所以从毫秒拼。
        override fun now(): Instant = Instant.fromEpochMilliseconds(
            LocalDate(2026, 3, 4).atStartOfDayIn(utc).toEpochMilliseconds() + 6L * 3_600_000L,
        )
    }

    private val resolver = TrainingDayResolver(
        checkInRepository = checkInRepository,
        planRepository = planRepository,
        todayClock = todayClockFor(clock, utc),
    )

    private fun stub(
        epochDay: Long,
        checkedIn: Boolean,
        planned: Boolean,
        completedSetsMask: Int = 0b011,
        weekStart: Long = DateUtils.weekStartMon1(epochDay),
    ) {
        // ⚠️ 默认带**两组**：`CheckIn()` 的 mask 默认是 0，而"有一行但完成了 0 组"
        // 恰恰是下面 `untickingTheLastSet...` 那条要单独测的残行 —— 
        // 早先这里用默认值当"练过了"，等于把残行当真实打卡喂给判据。
        every { checkInRepository.observeByDate(epochDay) } returns
            flowOf(
                if (checkedIn) {
                    listOf(CheckIn(id = 1L, exerciseId = 7L, dateEpochDay = epochDay, completedSetsMask = completedSetsMask))
                } else {
                    emptyList()
                }
            )
        every { planRepository.observeEffectivePlanForDay(any(), weekStart) } returns
            flowOf(if (planned) listOf(WeekPlan(id = 3L, exerciseId = 7L, dayOfWeek = 3)) else emptyList())
    }

    /**
     * 取消勾选最后一组之后（审查报告 P2-5）：行还在库里，只是"完成了 0 组"。
     *
     * 旧判据看的是**有没有行**，于是那一天仍按训练日给热量、streak 照加，
     * 而同屏那条写着"未完成"。热量给多给少是吃进去的差别，不是显示问题。
     */
    @Test
    fun untickingTheLastSetLeavesAResidueRowThatIsNotATrainingDay() = runTest {
        val pastDay = today - 1
        stub(epochDay = pastDay, checkedIn = true, planned = false, completedSetsMask = 0)

        assertFalse("完成了 0 组的残行不算练过", resolver(pastDay))
    }

    @Test
    fun actualCheckIn_makesItTrainingDay_evenWithoutAnyPlan() = runTest {
        stub(epochDay = today, checkedIn = true, planned = false)

        assertTrue("练过了就是训练日，哪怕当天没排课（临时练）", resolver(today))
    }

    @Test
    fun plannedAndStillToday_fallsBackToTrainingDay() = runTest {
        // 早上生成当天饮食时还没有打卡：只看打卡会把今天要练的那次训练抹掉。
        stub(epochDay = today, checkedIn = false, planned = true)

        assertTrue(resolver(today))
    }

    @Test
    fun plannedButPastAndNeverTicked_isRestDay() = runTest {
        // 这正是旧写法虚高的那一类：排了没练，仍按训练日给热量。
        val pastDay = today - 2
        stub(epochDay = pastDay, checkedIn = false, planned = true)

        assertFalse("过去且没练成 = 休息日", resolver(pastDay))
    }

    @Test
    fun futurePlannedDay_countsAsTrainingDay() = runTest {
        val futureDay = today + 5
        stub(epochDay = futureDay, checkedIn = false, planned = true)

        assertTrue("未来日只有计划可依据，按训练日", resolver(futureDay))
    }

    @Test
    fun neitherPlannedNorTicked_isRestDay() = runTest {
        stub(epochDay = today, checkedIn = false, planned = false)

        assertFalse(resolver(today))
    }

    @Test
    fun viewingAnotherWeek_asksThatWeekNotTheCurrentOne() = runTest {
        // 跨周口径回归：旧写法用 observePlansForDay(weekday)，内部固定取**当前周**，
        // 于是"看下周那天有没有课"其实是按本周判的。
        val nextWeekDay = today + 7
        val nextWeekStart = DateUtils.weekStartMon1(nextWeekDay)
        stub(epochDay = nextWeekDay, checkedIn = false, planned = true, weekStart = nextWeekStart)

        val result: Boolean = resolver(nextWeekDay)

        assertTrue(result)
        coVerify(exactly = 1) {
            planRepository.observeEffectivePlanForDay(
                dayOfWeek = DateUtils.weekdayMon1(nextWeekDay),
                weekStartEpochDay = nextWeekStart,
            )
        }
        assertEquals("下周与本周必须不是同一个周起点", true, nextWeekStart != DateUtils.weekStartMon1(today))
    }

    @Test
    fun observe_recomputesWhenCheckInAppears() = runTest {
        // 响应式版本：练完点下去，热量分支要立刻翻过来（今日页饮食区吃这条）。
        every { checkInRepository.observeByDate(today) } returns flowOf(emptyList())
        every { planRepository.observeEffectivePlanForDay(any(), any()) } returns flowOf(emptyList())

        val before: Boolean = resolver.observe(today).first()
        every { checkInRepository.observeByDate(today) } returns
            flowOf(
                // 带一组完成：`CheckIn()` 的 mask 默认 0，而"有行但 0 组"恰好不算训练日
                // （见 `untickingTheLastSet...`）。这条测试要的是"练完点下去"，样本得真是练过。
                listOf(CheckIn(id = 1L, exerciseId = 7L, dateEpochDay = today, completedSetsMask = 0b1)),
            )
        val after: Boolean = resolver.observe(today).first()

        assertEquals(false, before)
        assertEquals(true, after)
    }
}
