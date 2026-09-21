package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.BodyMetric
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.FoodNutrition
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealItem
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.model.ReviewNote
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.model.WeekDayDetail
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.MealItemRepository
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
        planId: Long? = null,
    ): CheckIn = CheckIn(
        exerciseId = exerciseId,
        dateEpochDay = epochDay,
        planId = planId,
        completedSetsMask = CheckIn.maskFromCount(sets),
        completedReps = reps,
        weightKg = weightKg,
        rpe = rpe,
    )

    private fun meal(
        epochDay: Long,
        kcal: Int,
        proteinG: Double,
        id: Long = 0L,
        isActive: Boolean = true,
        isCompleted: Boolean = false,
    ): Meal = Meal(
        id = id,
        dateEpochDay = epochDay,
        mealType = MealType.LUNCH,
        kcal = kcal,
        proteinG = proteinG,
        isActive = isActive,
        isCompleted = isCompleted,
    )

    /** 一条"实际记过"的条目（挂在 [mealId] 那一餐上）。 */
    private fun item(mealId: Long, kcal: Int, proteinG: Double): MealItem = MealItem(
        id = mealId * 100 + kcal,
        mealId = mealId,
        foodName = "测试食物",
        grams = 100.0,
        nutrition = FoodNutrition(kcal = kcal, proteinG = proteinG, carbsG = 0.0, fatG = 0.0),
    )

    private val checkInRepository = mockk<CheckInRepository>()
    private val exerciseRepository = mockk<ExerciseRepository>()
    private val bodyMetricRepository = mockk<BodyMetricRepository>()
    private val mealRepository = mockk<MealRepository>()
    private val mealItemRepository = mockk<MealItemRepository>()
    private val planRepository = mockk<PlanRepository>()

    /** 按区间过滤的假查库（与真实仓库语义一致：闭区间）。 */
    private fun stub(
        checkIns: List<CheckIn> = emptyList(),
        weights: List<BodyMetric> = emptyList(),
        meals: List<Meal> = emptyList(),
        itemsByDay: Map<Long, List<MealItem>> = emptyMap(),
        plannedWeekdays: List<Int> = listOf(1, 3, 5),
        effectiveRows: List<WeekPlan>? = null,
        retiredRows: List<WeekPlan> = emptyList(),
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
        coEvery { mealItemRepository.getByDate(any()) } answers {
            itemsByDay[firstArg<Long>()].orEmpty()
        }
        // B-9 后复盘按目标周取口径（useCase 传 weekStart）→ 用 any() 匹配任意周参数。
        // 默认给每个计划日一条生效行：distinct dayOfWeek 的个数仍等于 plannedWeekdays.size，
        // 与旧的 observePlannedWeekdays 桩语义一致。要测"练过的行被删掉"时传 effectiveRows 覆盖。
        every { planRepository.observeEffectivePlanForWeek(any()) } returns flowOf(
            effectiveRows ?: plannedWeekdays.map { day -> WeekPlan(dayOfWeek = day) },
        )
        // 分母还会回读"被这周打卡消费过的停用行"（走查 #1）。默认空 = 没有这类行，
        // 老用例的口径一字不变。
        coEvery { planRepository.getRowsForWeek(any()) } returns retiredRows
        coEvery { planRepository.getRepeatRows() } returns emptyList()
    }

    private fun useCase(): BuildWeeklyReviewUseCase = BuildWeeklyReviewUseCase(
        checkInRepository = checkInRepository,
        exerciseRepository = exerciseRepository,
        bodyMetricRepository = bodyMetricRepository,
        mealRepository = mealRepository,
        mealItemRepository = mealItemRepository,
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
    fun singleWeightInWeek_hasNoDelta_insteadOfZero() = runTest {
        // 真机上发现的坑：一周只称了一次 → 旧实现算出 0，界面上显示"体重变化 0"，
        // 会被读成"体重没变"。一次称重根本算不出"变化" → 必须是 null（界面显示 "—"）。
        stub(weights = listOf(weightMetric(epochDay = day(1), value = 74.6f)))

        val review = useCase()()

        assertEquals(74.6f, review.body.startWeightKg!!, 0.0001f)
        assertEquals(1, review.body.sampleCount)
        assertNull("只有一条记录 → 不许给 0", review.body.deltaKg)
    }

    @Test
    fun twoEqualWeightsInWeek_reportZeroDelta() = runTest {
        // 真的有两条、且数值相同 → 这时 0 是**真结论**（不是编的），必须照实给 0。
        stub(
            weights = listOf(
                weightMetric(epochDay = day(0), value = 74.6f),
                weightMetric(epochDay = day(3), value = 74.6f),
            ),
        )

        val review = useCase()()

        assertEquals(2, review.body.sampleCount)
        assertEquals(0f, review.body.deltaKg!!, 0.0001f)
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
                // 周一：打了勾 → 粗记 600 + 800 = 1400 kcal / 60g 蛋白
                meal(day(0), kcal = 600, proteinG = 30.0, isCompleted = true),
                meal(day(0), kcal = 800, proteinG = 30.0, isCompleted = true),
                // 周二：只有一条被"不吃这餐"软删掉 → 不算记录
                meal(day(1), kcal = 500, proteinG = 25.0, isActive = false, isCompleted = true),
                // 周三：900 kcal / 50g
                meal(day(2), kcal = 900, proteinG = 50.0, isCompleted = true),
            ),
        )

        val review = useCase()()

        assertEquals("只有周一、周三算「有记录的一天」", 2, review.diet.loggedDays)
        assertEquals("(1400+900)/2 = 1150（不是 7 天平均）", 1150, review.diet.avgKcal)
        assertEquals("(60+50)/2 = 55", 55, review.diet.avgProteinG)
        assertEquals("全是打勾估的 → 一天都不精确（界面据此标「约」）", 0, review.diet.preciseDays)
        assertFalse(review.notes.contains(ReviewNote.NO_DIET))
    }

    /**
     * 第 3 刀的承重墙：**AI 排了餐 ≠ 吃了**。
     * 旧口径直接 `sum(meals.kcal)`，于是"一周生成了计划但一口没记"会被报成
     * "记了 7 天、日均 2400 kcal" —— 训练侧 2026-09-20 刚为同一件事把计划与打卡分成两张表。
     */
    @Test
    fun diet_plannedButNeverLogged_countsNoDays() = runTest {
        stub(
            meals = (0..6).map { offset ->
                meal(day(offset), kcal = 800, proteinG = 40.0)
            },
        )

        val review = useCase()()

        assertEquals("排满 7 天但没勾也没记 → 0 天", 0, review.diet.loggedDays)
        assertNull(review.diet.avgKcal)
        assertTrue(review.notes.contains(ReviewNote.NO_DIET))
    }

    /** 明细优先：打了勾、也记了明细 → 只认明细，整餐值不再叠加。 */
    @Test
    fun diet_itemsBeatTheTick_andPreciseDaysCountsThem() = runTest {
        val lunch: Meal = meal(day(0), id = 11L, kcal = 800, proteinG = 40.0, isCompleted = true)
        stub(
            meals = listOf(lunch),
            itemsByDay = mapOf(day(0) to listOf(item(mealId = 11L, kcal = 350, proteinG = 22.0))),
        )

        val review = useCase()()

        assertEquals(1, review.diet.loggedDays)
        assertEquals("取明细的 350，不是打勾那餐的 800", 350, review.diet.avgKcal)
        assertEquals(1, review.diet.preciseDays)
    }

    /** 打勾估的 + 逐样记的混在一周：平均按各自口径合起来算，精确天数只数后者。 */
    @Test
    fun diet_mixesCoarseAndPreciseDays() = runTest {
        stub(
            meals = listOf(
                meal(day(0), id = 21L, kcal = 500, proteinG = 20.0, isCompleted = true),
                meal(day(1), id = 22L, kcal = 900, proteinG = 45.0, isCompleted = true),
            ),
            // 周二记了明细（300），所以那餐的整餐值 900 不再计入。
            itemsByDay = mapOf(day(1) to listOf(item(mealId = 22L, kcal = 300, proteinG = 30.0))),
        )

        val review = useCase()()

        assertEquals(2, review.diet.loggedDays)
        assertEquals("周一粗记 500 + 周二明细 300，日均 400", 400, review.diet.avgKcal)
        assertEquals("只有周二是逐样记的", 1, review.diet.preciseDays)
    }

    // ---------------- 逐日事实（星期格 + 日卡的数据源）----------------

    /** 计划组数按天摊开，且与周汇总同源 —— 日卡「12/35」那个分母就是这么来的。 */
    @Test
    fun dayDetails_carryPlannedSetsPerDay_andAgreeWithTheWeekTotal() = runTest {
        stub(checkIns = listOf(checkIn(exerciseId = 1L, epochDay = day(0), sets = 4)))

        val review = useCase()()

        // 桩：周一 / 周三 / 周五各一条生效行，targetSets 默认 3
        assertEquals(listOf(3, 0, 3, 0, 3, 0, 0), review.days.map { day -> day.plannedSets })
        assertEquals(9, review.training.plannedSets)
        assertEquals(
            "逐日计划组数之和必须等于周汇总，否则周卡和日卡是两套口径",
            review.training.plannedSets,
            review.days.sumOf { day -> day.plannedSets },
        )
    }

    /**
     * 练过的计划行被删掉 / 被重新生成回收之后，分母必须留住它（走查 #1）。
     *
     * 真机 9/14：当天生效行只剩 1 条 3 组，实际练了 10 组（另两条计划行已 `is_active=0`），
     * 日卡于是显示「10/3」—— 看着像用户超练了三倍，真相是那两条计划被请出了分母。
     */
    @Test
    fun trainedThenDeletedPlanRow_staysInTheDenominator() = runTest {
        stub(
            checkIns = listOf(
                checkIn(exerciseId = 1L, epochDay = day(0), sets = 3, planId = 91L),
                checkIn(exerciseId = 2L, epochDay = day(0), sets = 4, planId = 13L),
            ),
            effectiveRows = listOf(WeekPlan(id = 91L, exerciseId = 1L, dayOfWeek = 1, targetSets = 3)),
            retiredRows = listOf(
                WeekPlan(id = 13L, exerciseId = 2L, dayOfWeek = 1, targetSets = 4, isActive = false),
            ),
        )

        val review = useCase()()

        assertEquals("生效 3 组 + 练过之后才被删的 4 组", 7, review.training.plannedSets)
        assertEquals(7, review.training.totalSets)
        assertEquals("日卡分母与周汇总同源：周一那一格也是 7", 7, review.days.first().plannedSets)
    }

    /** 反向守卫：从没练过的删槽位**不该**回来虚增分母，否则完成率凭空变低。 */
    @Test
    fun neverTrainedDeletedPlanRow_staysOutOfTheDenominator() = runTest {
        stub(
            checkIns = listOf(checkIn(exerciseId = 1L, epochDay = day(0), sets = 3, planId = 91L)),
            effectiveRows = listOf(WeekPlan(id = 91L, exerciseId = 1L, dayOfWeek = 1, targetSets = 3)),
            retiredRows = listOf(
                WeekPlan(id = 13L, exerciseId = 2L, dayOfWeek = 1, targetSets = 4, isActive = false),
            ),
        )

        assertEquals(3, useCase()().training.plannedSets)
    }

    /** 实际组数按**全部**当天打卡算，含查不到动作名、不进 `items` 的那些行。 */
    @Test
    fun dayDetails_completedSetsCountsRowsThatHaveNoName() = runTest {
        stub(
            checkIns = listOf(
                checkIn(exerciseId = 1L, epochDay = day(0), sets = 4),
                checkIn(exerciseId = 999L, epochDay = day(0), sets = 2),
            ),
        )

        val monday: WeekDayDetail = useCase()().days.first()

        assertEquals("查不到名字的行不进 items（不写「未知动作」占位）", 1, monday.items.size)
        assertEquals("但那 2 组真的做完了，组数不能少算", 6, monday.completedSets)
    }

    /** 体重只落在称的那一天；其它天是 `null` —— 不是 0，也不许沿用上一条。 */
    @Test
    fun dayDetails_weightLandsOnlyOnTheWeighInDay() = runTest {
        stub(weights = listOf(weightMetric(epochDay = day(1), value = 74.6f)))

        val review = useCase()()

        assertNull(review.days[0].weightKg)
        assertEquals(74.6f, review.days[1].weightKg)
        assertNull("不能把周二那个数顺延给周三", review.days[2].weightKg)
    }

    /** 精度是**逐天**的事实 —— 折叠成周级 `preciseDays` 之后日卡就再也标不出「约」了。 */
    @Test
    fun dayDetails_dietPrecisionIsPerDay() = runTest {
        stub(
            meals = listOf(
                meal(day(0), id = 21L, kcal = 500, proteinG = 20.0, isCompleted = true),
                meal(day(1), id = 22L, kcal = 900, proteinG = 45.0, isCompleted = true),
            ),
            itemsByDay = mapOf(day(1) to listOf(item(mealId = 22L, kcal = 300, proteinG = 30.0))),
        )

        val review = useCase()()

        assertEquals("周一只打了勾 → 当天数字就是整餐值", 500, review.days[0].kcal)
        assertFalse("周一不是逐样记的，日卡要标「约」", review.days[0].dietPrecise)
        assertEquals("周二记了明细 → 取 300 不是打勾那餐的 900", 300, review.days[1].kcal)
        assertTrue(review.days[1].dietPrecise)
        assertEquals(20, review.days[0].proteinG)
        assertEquals(1, review.diet.preciseDays)
    }

    /** 没记的那天必须是 `null`，不能是 0 —— 0 会被日卡读成"那天一口没吃"。 */
    @Test
    fun dayDetails_unloggedDaysAreNullNotZero() = runTest {
        stub(meals = listOf(meal(day(0), id = 31L, kcal = 600, proteinG = 30.0, isCompleted = true)))

        val review = useCase()()

        assertEquals(600, review.days[0].kcal)
        assertNull(review.days[1].kcal)
        assertNull(review.days[1].proteinG)
        assertFalse(review.days[1].dietPrecise)
        assertEquals("7 天全都要出现，空格也得能点开", 7, review.days.size)
    }

    @Test
    fun zeroKcalDay_doesNotCountAsLogged() = runTest {
        stub(meals = listOf(meal(day(0), kcal = 0, proteinG = 0.0, isCompleted = true)))

        val review = useCase()()

        assertEquals("打了勾但那餐一个数字都没有 → 计入只会把均值拉向 0", 0, review.diet.loggedDays)
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
    fun sameInputYieldsSameOutput() = runTest {        stub(
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
