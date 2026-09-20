package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * **勾选身份的接线测试**：补录 / 详细打卡 / 补卡这三条写库路径，
 * 是不是真的把「这一行原来勾了哪几组」读出来再折算。
 *
 * ## 为什么单独立一个文件
 * [CheckIn.mergedMask] 本身有 [CheckInSetMaskTest] 逐条打过了，但那只是"函数对不对"。
 * 真正的事故形态是**接线断掉**：某个用例改回 `maskFromCount(sets)`，或者忘了查旧记录、
 * 把 `previousMask` 传成 `0` —— 函数全对、测试全绿，而用户"勾过第 1、3 组"这件事
 * 在一次补录之后静默变成"第 1、2 组"。mask 是全 app 唯一记录勾选身份的地方，
 * 没有第二份数据能把它还原回来。
 *
 * ## 用例数值是挑过的
 * 每条都选**接线断了就会失败**的组合：`previous = 0b101` + 提交 2 组，
 * 正确结果是 `0b101`（保留原来那两组），而 `maskFromCount(2)` 会给 `0b011`。
 * 用 3 组配 `0b101` 就测不出来（两种算法都得 `0b111`），所以不那么写。
 *
 * 注：「一键打卡」([QuickCheckInUseCase]) 走 `maskFromCount(plan.targetSets)` 是**故意的**
 * （一键 = 目标组全勾满），不在本文件的守护范围内，见 [QuickCheckInUseCaseTest]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckInMaskWiringTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val utc = TimeZone.UTC
    private val epochDay: Long = LocalDate(2026, 2, 21).toEpochDays().toLong()
    private val clock = object : Clock {
        override fun now(): Instant =
            Instant.fromEpochMilliseconds(LocalDate(2026, 2, 21).atStartOfDayIn(utc).toEpochMilliseconds())
    }

    private val checkInRepository = mockk<CheckInRepository>(relaxed = true)
    private val exerciseRepository = mockk<ExerciseRepository>(relaxed = true)

    private val detailed = DetailedCheckInUseCase(
        checkInRepository = checkInRepository,
        exerciseRepository = exerciseRepository,
        clock = clock,
        timeZone = utc,
    )
    private val backfill = BackfillCheckInUseCase(
        checkInRepository = checkInRepository,
        clock = clock,
        timeZone = utc,
    )

    /** 抓住写库那一条；必须在调用用例**之前**装好桩，否则 capture 抓不到。 */
    private val saved = slot<CheckIn>()

    /** 让仓库"报告"这一行已经勾了 [previousMask]，并装好写库捕获。 */
    private fun givenPrevious(previousMask: Int?) {
        coEvery { checkInRepository.getForExerciseOnDate(any(), any()) } returns
            previousMask?.let { mask ->
                CheckIn(id = 9L, exerciseId = 7L, dateEpochDay = epochDay, completedSetsMask = mask)
            }
        coEvery { checkInRepository.upsert(capture(saved)) } returns 1L
    }

    @Test
    fun detailedCheckIn_shrinkingCount_keepsTheOriginallyTickedPositions() = runTest {
        givenPrevious(previousMask = 0b101)

        detailed(
            exerciseId = 7L,
            planId = null,
            epochDay = epochDay,
            sets = 2,
            reps = 12,
        )

        val written = saved.captured
        assertEquals("接线断了才会变成 0b011 —— 保留第 1、3 组", 0b101, written.completedSetsMask)
        assertEquals(2, written.completedSets)
    }

    @Test
    fun detailedCheckIn_growingCount_keepsExistingAndFillsLowestFreeBit() = runTest {
        givenPrevious(previousMask = 0b1001)

        detailed(
            exerciseId = 7L,
            planId = null,
            epochDay = epochDay,
            sets = 3,
            reps = 10,
        )

        val written = saved.captured
        assertEquals("第 1、4 组原位不动，缺的 1 组从最小空位（第 2 组）补上", 0b1011, written.completedSetsMask)
    }

    @Test
    fun backfill_shrinkingCount_keepsTheOriginallyTickedPositions() = runTest {
        givenPrevious(previousMask = 0b101)

        backfill(exerciseId = 7L, epochDay = epochDay, sets = 2, reps = 12)

        assertEquals(0b101, saved.captured.completedSetsMask)
    }

    @Test
    fun noPreviousRecord_matchesMaskFromCount() = runTest {
        // 反例守护：没有旧记录时不该凭空造出高位，等价于 maskFromCount(2)。
        givenPrevious(previousMask = null)

        detailed(
            exerciseId = 7L,
            planId = null,
            epochDay = epochDay,
            sets = 2,
            reps = 12,
        )

        val written = saved.captured
        assertEquals(CheckIn.maskFromCount(2), written.completedSetsMask)
        assertEquals(0b011, written.completedSetsMask)
    }
}
