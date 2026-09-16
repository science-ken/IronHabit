package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.BodyMetric
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.model.ReviewNote
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.PlanRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BuildWeeklyReviewUseCase] 单测（P2 · 周复盘聚合）。
 *
 * 核心不变量：
 * 1. **不猜**：拿不到的体重 / RPE / 饮食一律 `null`，绝不用 `0` 冒充"没变化"；
 * 2. **只读**：本用例不写任何仓库（没有 upsert / delete 调用）；
 * 3. **确定性 + 7 天齐全**：同一份数据必得同一结果，`days` 恒为 7 项；
 * 4. 停滞只在**有记录的周**之间连数（断档不算停滞）。
 */
class BuildWeeklyReviewUseCaseTest {

    /** 固定"今天" = 2026-09-16（周三），本周 = 2026-09-14（周一）～ 09-20（周日）。 */
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-16T10:00:00Z")
    }

    private val weekStart: Long = LocalDate(2026, 9, 14).toEpochDays().toLong()

    private fun day(offset: Int): Long = weekStart + offset

    private val exercises: List<Exercise> = listOf(
        exercise(1L, "杠铃深蹲"),
        exercise(2L, "卧推"),
        exercise(3L, "引体向上"),
    )

    private fun exercise(id: Long, name: String): Exercise = Exercise(
        id = id,
        name = name,
        category = ExerciseCategory.STRENGTH,
        muscleGroups = listOf("测试肌群"),
        isActive = true,
        defaultSets = 3,
        defaultReps = 10,
    )

    private fun checkIn(
        exerciseId: Long,
        epochDay: Long,
        sets: Int = 3,
        reps: Int = 10,
        weightKg: Float? = 40f,
        rpe: Int? = null,
    ): CheckIn = CheckIn(
        exerciseId = exerciseId,
        dateEpochDay = epochDay,
        completedSetsMask = CheckIn.maskFromCount(sets),
        completedReps = reps,
        weightKg = weightKg,
        rpe = rpe,
    )

    private fun meal(epochDay: Long, kcal: Int, proteinG: Double, isActive: Boolean = true): Meal = Meal(
        dateEpochDay = epochDay,
        mealType = MealType.LUNCH,
        kcal = kcal,
        proteinG = proteinG,
        isActive = isActive,
    )

    private val checkInRepository = mockk<CheckInRepository>()
    private val exerciseRepository = mockk<ExerciseRepository>()
    private val bodyMetricRepository = mockk<BodyMetricRepository>()
    private val mealRepository = mockk<MealRepository>()
    private val planRepository = mockk<PlanRepository>()

    /** 按区间过滤的假查库（与真实仓库语义一致：闭区间）。 */
    private fun stub(
        checkIns: List<CheckIn> = emptyList(),
        weights: List<BodyMetric> = emptyList(),
        meals: List<Meal> = emptyList(),
        plannedWeekdays: List<Int> = listOf(1, 3, 5),
    ) {
        every { checkInRepository.observeBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            flowOf(checkIns.filter { it.dateEpochDay in start..end })
        }
        every { exerciseRepository.observeActive() } returns flowOf(exercises)
        every { bodyMetricRepository.observeByType(BodyMetricType.WEIGHT) } returns flowOf(weights)
        coEvery { mealRepository.getMealsIncludingInactive(any()) } answers {
            val epochDay = firstArg<Long>()
            meals.filter { it.dateEpochDay == epochDay }
        }
        every { planRepository.observePlannedWeekdays() } returns flowOf(plannedWeekdays)
    }

    private fun useCase(): BuildWeeklyReviewUseCase = BuildWeeklyReviewUseCase(
        checkInRepository = checkInRepository,
        exerciseRepository = exerciseRepository,
        bodyMetricRepository = bodyMetricRepository,
        mealRepository = mealRepository,
        planRepository = planRepository,
        clock = fixedClock,
        timeZone = TimeZone.UTC,
    )

    // ---------------- 空周：不猜、不崩、7 天齐全 ----------------

    @Test
    fun emptyWeek_reportsZerosAndHonestNotes() = runTest {
        stub()

        val review = useCase()()

        assertEquals("默认取今天所在周的周一", weekStart, review.weekStartEpochDay)
        assertEquals(weekStart + 6, review.weekEndEpochDay)
        assertEquals(0, review.training.completedDays)
        assertEquals(0, review.training.totalSets)
        assertEquals(0f, review.training.totalVolumeKg, 0.0001f)
        assertNull("没打卡 → 平均 RPE 必须是 null，不能是 0", review.training.avgRpe)
        assertNull("没记体重 → delta 必须是 null，不能是 0", review.body.deltaKg)
        assertNull(review.diet.avgKcal)
        assertNull(review.diet.avgProteinG)
        assertEquals("计划天数来自计划表", 3, review.training.plannedDays)
        assertTrue(review.notes.contains(ReviewNote.NO_CHECKIN))
        assertTrue(review.notes.contains(ReviewNote.NO_WEIGHT))
        assertTrue(review.notes.contains(ReviewNote.NO_DIET))
        assertEquals("一周 7 天必须全都在（含空天）", 7, review.days.size)
        assertTrue("空周每天都没有明细", review.days.all { it.items.isEmpty() })
        assertEquals(
            "日期要按顺序铺满整周",
            (0..6).map { weekStart + it },
            review.days.map { it.dateEpochDay },
        )
    }

    @Test
    fun missingDay_keepsTheDayWithNoItems() = runTest {
        stub(checkIns = listOf(checkIn(exerciseId = 1L, epochDay = day(1), rpe = 7)))

        val review = useCase()()

        assertEquals(7, review.days.size)
        assertEquals("有打卡那天有明细", 1, review.days[1].items.size)
        assertEquals("没打卡的天保留、但 items 为空", 0, review.days[0].items.size)
        assertTrue(review.days[0].dateEpochDay == weekStart)
    }

    // ---------------- 训练汇总 ----------------

    @Test
    fun volumeOnlyCountsWeightedRecords() = runTest {
        stub(
            checkIns = listOf(
                // 有重量：40 × 10 次 × 3 组 = 1200
                checkIn(exerciseId = 1L, epochDay = day(0), sets = 3, reps = 10, weightKg = 40f),
                // 自重（无重量）→ 不计入容量，但组数要计入
                checkIn(exerciseId = 3L, epochDay = day(2), sets = 4, reps = 8, weightKg = null),
            ),
        )

        val review = useCase()()

        assertEquals(1200f, review.training.totalVolumeKg, 0.0001f)
        assertEquals("组数包含自重动作的 4 组", 7, review.training.totalSets)
        assertEquals(2, review.training.completedDays)
    }

    @Test
    fun checkInsWithoutRpe_reportNoRpeNote() = runTest {
        stub(checkIns = listOf(checkIn(exerciseId = 1L, epochDay = day(0), rpe = null)))

        val review = useCase()()

        assertNull(review.training.avgRpe)
        assertTrue(review.notes.contains(ReviewNote.NO_RPE))
        assertFalse("有打卡就不该说「没有打卡」", review.notes.contains(ReviewNote.NO_CHECKIN))
    }

    @Test
    fun averageRpe_isRoundedToOneDecimal() = runTest {
        stub(
            checkIns = listOf(
                checkIn(exerciseId = 1L, epochDay = day(0), rpe = 7),
                checkIn(exerciseId = 2L, epochDay = day(1), rpe = 7),
                checkIn(exerciseId = 3L, epochDay = day(2), rpe = 7),
                checkIn(exerciseId = 1L, epochDay = day(3), rpe = 8),
            ),
        )

        val review = useCase()()

        // (7+7+7+8)/4 = 7.25 → 7.3（四舍五入到 1 位小数）
        assertEquals(7.3f, review.training.avgRpe!!, 0.0001f)
    }

    // ---------------- 趋势：进步 / 停滞 ----------------

    @Test
    fun heavierThanLastWeek_isReportedAsProgressed() = runTest {
        stub(
            checkIns = listOf(
                // 上周：40kg
                checkIn(exerciseId = 1L, epochDay = day(-7), weightKg = 40f, rpe = 6),
                // 本周：42.5kg
                checkIn(exerciseId = 1L, epochDay = day(0), weightKg = 42.5f, rpe = 6),
            ),
        )

        val review = useCase()()

        val trend = review.training.progressed.single()
        assertEquals("杠铃深蹲", trend.exerciseName)
        assertEquals(40f, trend.previousWeightKg!!, 0.0001f)
        assertEquals(42.5f, trend.latestWeightKg!!, 0.0001f)
        assertEquals(0, trend.stagnantWeeks)
        assertTrue("有进步就不能进停滞列表", review.training.stalled.isEmpty())
    }

    @Test
    fun threeRecordedWeeksWithoutProgress_isStalled() = runTest {
        stub(
            checkIns = listOf(
                checkIn(exerciseId = 2L, epochDay = day(-21), weightKg = 30f),
                checkIn(exerciseId = 2L, epochDay = day(-14), weightKg = 30f),
                checkIn(exerciseId = 2L, epochDay = day(-7), weightKg = 30f),
                checkIn(exerciseId = 2L, epochDay = day(0), weightKg = 30f),
            ),
        )

        val review = useCase()()

        val trend = review.training.stalled.single()
        assertEquals("卧推", trend.exerciseName)
        assertEquals(TrainingReview.STALLED_WEEKS_THRESHOLD, trend.stagnantWeeks)
        assertTrue(review.training.progressed.isEmpty())
    }

    @Test
    fun twoRecordedWeeksWithoutProgress_isNotYetStalled() = runTest {
        stub(
            checkIns = listOf(
                checkIn(exerciseId = 2L, epochDay = day(-14), weightKg = 30f),
                checkIn(exerciseId = 2L, epochDay = day(-7), weightKg = 30f),
                checkIn(exerciseId = 2L, epochDay = day(0), weightKg = 30f),
            ),
        )

        val review = useCase()()

        assertTrue("只连续 2 周没涨 → 还不算停滞", review.training.stalled.isEmpty())
        assertTrue(review.training.progressed.isEmpty())
    }

    @Test
    fun gapWeeks_doNotCountAsStagnation() = runTest {
        // 中间断档（该周没练这个动作）→ 停止计数：一个刚开始练的动作不该被判"停滞 3 周"。
        stub(
            checkIns = listOf(
                checkIn(exerciseId = 3L, epochDay = day(-21), weightKg = 50f),
                checkIn(exerciseId = 3L, epochDay = day(0), weightKg = 50f),
            ),
        )

        val review = useCase()()

        assertTrue("断档不算停滞", review.training.stalled.isEmpty())
        val trend = (review.training.progressed + review.training.stalled)
        assertTrue("既没进步也没停滞 → 两个列表都不出现", trend.isEmpty())
    }

    @Test
    fun exerciseWithoutRecordThisWeek_isExcludedFromTrends() = runTest {
        stub(
            checkIns = listOf(
                checkIn(exerciseId = 1L, epochDay = day(-7), weightKg = 40f),
                checkIn(exerciseId = 1L, epochDay = day(-14), weightKg = 35f),
                // 本周只练了 2L
                checkIn(exerciseId = 2L, epochDay = day(0), weightKg = 30f),
            ),
        )

        val review = useCase()()

        assertTrue(review.training.progressed.isEmpty())
        assertTrue(review.training.stalled.isEmpty())
    }

    @Test
    fun unknownExerciseId_isSkippedInsteadOfShowingPlaceholderName() = runTest {
        stub(
            checkIns = listOf(
                checkIn(exerciseId = 999L, epochDay = day(0), weightKg = 20f),
                checkIn(exerciseId = 1L, epochDay = day(0), weightKg = 40f),
            ),
        )

        val review = useCase()()

        assertEquals("查不到名字的动作不进明细（不写「未知动作」占位）", 1, review.days[0].items.size)
        assertEquals("杠铃深蹲", review.days[0].items.single().exerciseName)
    }

    // ---------------- 体重 ----------------

    @Test
    fun twoWeightsInWeek_computeDelta() = runTest {
        stub(
            weights = listOf(
                weightMetric(epochDay = day(4), value = 74.2f),
                weightMetric(epochDay = day(0), value = 74.6f),
            ),
        )

        val review = useCase()()

        assertEquals("最早的那条", 74.6f, review.body.startWeightKg!!, 0.0001f)
        assertEquals("最新的那条", 74.2f, review.body.latestWeightKg!!, 0.0001f)
        assertEquals(-0.4f, review.body.deltaKg!!, 0.0001f)
    }

    @Test
    fun weightOutsideWeek_isIgnored() = runTest {
        stub(
            weights = listOf(
                weightMetric(epochDay = day(-3), value = 80f),
                weightMetric(epochDay = day(9), value = 70f),
            ),
        )

        val review = useCase()()

        assertNull(review.body.startWeightKg)
        assertNull(review.body.deltaKg)
        assertTrue(review.notes.contains(ReviewNote.NO_WEIGHT))
    }

    // ---------------- 饮食 ----------------

    @Test
    fun diet_averageUsesOnlyLoggedDays_andIgnoresInactiveMeals() = runTest {
        stub(
            meals = listOf(
                // 周一：600 + 800 = 1400 kcal / 60g 蛋白
                meal(day(0), kcal = 600, proteinG = 30.0),
                meal(day(0), kcal = 800, proteinG = 30.0),
                // 周二：只有一条被"不吃这餐"软删掉 → 不算记录
                meal(day(1), kcal = 500, proteinG = 25.0, isActive = false),
                // 周三：900 kcal / 50g
                meal(day(2), kcal = 900, proteinG = 50.0),
            ),
        )

        val review = useCase()()

        assertEquals("只有周一、周三算「有记录的一天」", 2, review.diet.loggedDays)
        assertEquals("(1400+900)/2 = 1150（不是 7 天平均）", 1150, review.diet.avgKcal)
        assertEquals("(60+50)/2 = 55", 55, review.diet.avgProteinG)
        assertFalse(review.notes.contains(ReviewNote.NO_DIET))
    }

    @Test
    fun zeroKcalDay_doesNotCountAsLogged() = runTest {
        stub(meals = listOf(meal(day(0), kcal = 0, proteinG = 0.0)))

        val review = useCase()()

        assertEquals(0, review.diet.loggedDays)
        assertNull(review.diet.avgKcal)
        assertTrue(review.notes.contains(ReviewNote.NO_DIET))
    }

    // ---------------- 周界与进行中的一周 ----------------

    @Test
    fun weekStartOf_isMondayAligned_forAnyDayIncludingNegativeEpochDays() = runTest {
        stub()

        // 2026-09-16 是周三 → 本周一 = 09-14
        assertEquals(weekStart, useCase()().weekStartEpochDay)
        // 传周日（09-20）也归一化到同一个周一
        assertEquals(
            weekStart,
            useCase()(LocalDate(2026, 9, 20).toEpochDays().toLong()).weekStartEpochDay,
        )
        // epochDay -3 = 1969-12-29（周一）→ 必须原样返回 -3；epochDay 0 = 周四 → 归到 -3
        assertEquals(-3L, useCase()(-3L).weekStartEpochDay)
        assertEquals(-3L, useCase()(0L).weekStartEpochDay)
    }

    @Test
    fun weekNotFinishedYet_reportsInProgressNote() = runTest {
        stub(checkIns = listOf(checkIn(exerciseId = 1L, epochDay = day(0), rpe = 7)))

        val review = useCase()()

        assertTrue("今天(周三)还没到周日 → 这一周没过完", review.notes.contains(ReviewNote.WEEK_IN_PROGRESS))
    }

    @Test
    fun finishedWeek_hasNoInProgressNote() = runTest {
        stub(checkIns = listOf(checkIn(exerciseId = 1L, epochDay = day(0), rpe = 7)))

        // 查上一周（9/7–9/13）：已经过完了
        val review = useCase()(LocalDate(2026, 9, 7).toEpochDays().toLong())

        assertFalse(review.notes.contains(ReviewNote.WEEK_IN_PROGRESS))
    }

    @Test
    fun sameInputYieldsSameOutput() = runTest {
        stub(
            checkIns = listOf(
                checkIn(exerciseId = 1L, epochDay = day(-7), weightKg = 40f, rpe = 6),
                checkIn(exerciseId = 1L, epochDay = day(0), weightKg = 42.5f, rpe = 6),
                checkIn(exerciseId = 2L, epochDay = day(1), weightKg = 30f, rpe = 9),
            ),
            weights = listOf(weightMetric(epochDay = day(0), value = 74.6f)),
            meals = listOf(meal(day(0), kcal = 1400, proteinG = 60.0)),
        )

        assertEquals("确定性：同输入必同输出", useCase()(), useCase()())
    }

    private fun weightMetric(epochDay: Long, value: Float): BodyMetric = BodyMetric(
        type = BodyMetricType.WEIGHT,
        value = value,
        unit = "kg",
        dateEpochDay = epochDay,
    )
}
