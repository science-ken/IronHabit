package com.ironhabit.app.verify

import com.ironhabit.app.domain.model.BodyMetric
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.BodyReview
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.DietReview
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseTrend
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekDayDetail
import com.ironhabit.app.domain.model.WeekItemDetail
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase
import com.ironhabit.app.domain.usecase.ExportWeekPackageUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2（周复盘 + 数据包）对抗性复核 —— 目标是**证伪**。
 *
 * 只调用公开 API：
 * - [BuildWeeklyReviewUseCase.invoke]\(weekStartEpochDay) 与其 public companion `weekStartOf`
 * - [ExportWeekPackageUseCase.invoke]\(review, includeDetails)
 * - 公开模型 [WeeklyReview] / [TrainingReview] / [BodyReview] / [DietReview] / [ExerciseTrend]
 *
 * JSON 断言一律**按 JSON 路径取值**（不反序列化回 DTO），这样"字段名写错"一定会被抓到。
 */
class P2AdversarialTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-16T10:00:00Z")
    }

    /** 2026-09-14（周一）～ 09-20（周日）。 */
    private val weekStart: Long = LocalDate(2026, 9, 14).toEpochDays().toLong()

    private fun day(offset: Int): Long = weekStart + offset

    private val exercises: List<Exercise> = listOf(
        exercise(1L, "杠铃深蹲"),
        exercise(2L, "卧推"),
        exercise(3L, "引体向上"),
    )

    private fun exercise(
        id: Long,
        name: String,
        category: ExerciseCategory = ExerciseCategory.STRENGTH,
    ): Exercise = Exercise(
        id = id,
        name = name,
        category = category,
        muscleGroups = listOf("腿部"),
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

    private fun weight(epochDay: Long, value: Float): BodyMetric = BodyMetric(
        type = BodyMetricType.WEIGHT,
        value = value,
        unit = "kg",
        dateEpochDay = epochDay,
    )

    private val checkInRepository = mockk<CheckInRepository>()
    private val exerciseRepository = mockk<ExerciseRepository>()
    private val bodyMetricRepository = mockk<BodyMetricRepository>()
    private val mealRepository = mockk<MealRepository>()
    private val planRepository = mockk<PlanRepository>()
    private val settingsRepository = mockk<SettingsRepository>()

    private fun stubReview(
        checkIns: List<CheckIn> = emptyList(),
        weights: List<BodyMetric> = emptyList(),
        plannedWeekdays: List<Int> = listOf(1, 3, 5),
    ) {
        every { checkInRepository.observeBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            flowOf(checkIns.filter { it.dateEpochDay in start..end })
        }
        every { exerciseRepository.observeActive() } returns flowOf(exercises)
        every { bodyMetricRepository.observeByType(BodyMetricType.WEIGHT) } returns flowOf(weights)
        coEvery { mealRepository.getMealsIncludingInactive(any()) } returns emptyList()
        every { planRepository.observePlannedWeekdays(any()) } returns flowOf(plannedWeekdays)
    }

    private fun reviewUseCase(
        clock: Clock = fixedClock,
        timeZone: TimeZone = TimeZone.UTC,
    ): BuildWeeklyReviewUseCase = BuildWeeklyReviewUseCase(
        checkInRepository = checkInRepository,
        exerciseRepository = exerciseRepository,
        bodyMetricRepository = bodyMetricRepository,
        mealRepository = mealRepository,
        mealItemRepository = mockk<com.ironhabit.app.domain.repository.MealItemRepository>(relaxed = true),
        planRepository = planRepository,
        clock = clock,
        timeZone = timeZone,
    )

    private fun exportUseCase(
        profile: UserProfile = UserProfile(),
        library: List<Exercise> = exercises,
    ): ExportWeekPackageUseCase {
        every { settingsRepository.profile() } returns flowOf(profile)
        every { exerciseRepository.observeActive() } returns flowOf(library)
        return ExportWeekPackageUseCase(
            settingsRepository = settingsRepository,
            exerciseRepository = exerciseRepository,
            clock = fixedClock,
            timeZone = TimeZone.UTC,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun String.json() = Json.parseToJsonElement(this).jsonObject

    // =================================================================
    // ① 体重：一条记录 → delta 必须是 null，不能是 0
    // =================================================================

    @Test
    fun singleWeightInWeek_deltaIsNullNotZero() = runTest {
        stubReview(weights = listOf(weight(day(1), 74.6f)))

        val review = reviewUseCase()()

        assertEquals(74.6f, review.body.startWeightKg!!, 0.0001f)
        assertEquals(1, review.body.sampleCount)
        assertNull("一次称重算不出「变化」→ 必须 null，给 0 等于编造「体重没变」", review.body.deltaKg)
    }

    @Test
    fun twoWeightsInWeek_zeroDeltaIsOnlyReportedWhenBothSamplesExist() = runTest {
        stubReview(
            weights = listOf(weight(day(0), 74.6f), weight(day(3), 74.6f)),
        )

        val review = reviewUseCase()()

        assertEquals(2, review.body.sampleCount)
        assertEquals("真的有两条且相等 → 0 是真结论，必须照实给", 0f, review.body.deltaKg!!, 0.0001f)
    }

    @Test
    fun weightsOutsideTheWeek_areIgnored() = runTest {
        stubReview(
            weights = listOf(weight(day(-1), 70f), weight(day(7), 80f), weight(day(2), 75f)),
        )

        val review = reviewUseCase()()

        assertEquals("周外两条不算，本周只有 1 条 → delta 仍是 null", 1, review.body.sampleCount)
        assertNull(review.body.deltaKg)
    }

    // =================================================================
    // ② 停滞：只在有记录的周之间连数；断档停止；上界 3
    // =================================================================

    @Test
    fun gapWeek_stopsCountingStagnation() = runTest {
        // 桶 0（3 周前）有记录，桶 1、2 断档，本周也有记录 → 断档即停，不应判停滞。
        stubReview(
            checkIns = listOf(
                checkIn(exerciseId = 1L, epochDay = day(-21), weightKg = 50f),
                checkIn(exerciseId = 1L, epochDay = day(0), weightKg = 50f),
            ),
        )

        val review = reviewUseCase()()

        assertTrue("中间断档 → 不该被判停滞", review.training.stalled.isEmpty())
        assertTrue("也没进步 → 两个列表都不出现", review.training.progressed.isEmpty())
    }

    @Test
    fun gapInTheMiddleOfTheWindow_stopsCountingAtTheGap() = runTest {
        // 桶 0 有、桶 1 断、桶 2 有、本周有 → 只连数 1 周。
        stubReview(
            checkIns = listOf(
                checkIn(exerciseId = 1L, epochDay = day(-21), weightKg = 50f),
                checkIn(exerciseId = 1L, epochDay = day(-7), weightKg = 50f),
                checkIn(exerciseId = 1L, epochDay = day(0), weightKg = 50f),
            ),
        )

        val review = reviewUseCase()()

        assertTrue("断档之后的周不该接着数", review.training.stalled.isEmpty())
    }

    @Test
    fun twoFlatWeeksNotStalled_threeFlatWeeksStalled() = runTest {
        stubReview(
            checkIns = listOf(
                checkIn(exerciseId = 2L, epochDay = day(-14), weightKg = 30f),
                checkIn(exerciseId = 2L, epochDay = day(-7), weightKg = 30f),
                checkIn(exerciseId = 2L, epochDay = day(0), weightKg = 30f),
            ),
        )
        assertEquals("连续 2 周没涨 → 还不够门槛", 0, reviewUseCase()().training.stalled.size)

        stubReview(
            checkIns = listOf(
                checkIn(exerciseId = 2L, epochDay = day(-21), weightKg = 30f),
                checkIn(exerciseId = 2L, epochDay = day(-14), weightKg = 30f),
                checkIn(exerciseId = 2L, epochDay = day(-7), weightKg = 30f),
                checkIn(exerciseId = 2L, epochDay = day(0), weightKg = 30f),
            ),
        )
        val stalled = reviewUseCase()().training.stalled
        assertEquals("连续 3 周没涨 → 判停滞", 1, stalled.size)
        assertEquals(3, stalled.single().stagnantWeeks)
    }

    @Test
    fun stagnantWeeksNeverExceedsThree_windowIsOnlyFourBuckets() = runTest {
        // 连续 6 周都一样 → 窗口只有「本周 + 前 3 周」4 个桶，所以上限恒为 3。
        stubReview(
            checkIns = (0..5).map { weekBack ->
                checkIn(exerciseId = 1L, epochDay = day(-7 * weekBack), weightKg = 60f)
            },
        )

        val review = reviewUseCase()()

        val trend = review.training.stalled.single()
        assertEquals("stagnantWeeks 的上界就是阈值 3", 3, trend.stagnantWeeks)
        assertTrue(
            "任何动作的 stagnantWeeks 都不该 > 3",
            review.training.stalled.all { it.stagnantWeeks <= TrainingReview.STALLED_WEEKS_THRESHOLD },
        )
    }

    @Test
    fun weightWentDown_countsAsStagnationNotProgress() = runTest {
        stubReview(
            checkIns = listOf(
                checkIn(exerciseId = 1L, epochDay = day(-21), weightKg = 60f),
                checkIn(exerciseId = 1L, epochDay = day(-14), weightKg = 60f),
                checkIn(exerciseId = 1L, epochDay = day(-7), weightKg = 60f),
                checkIn(exerciseId = 1L, epochDay = day(0), weightKg = 55f),
            ),
        )

        val review = reviewUseCase()()

        assertTrue("退步不是进步", review.training.progressed.isEmpty())
        assertEquals("退步按停滞算（不涨即停滞）", 1, review.training.stalled.size)
    }

    @Test
    fun firstWeekOfAnExercise_isNeverStalled() = runTest {
        // 只在本周出现过的动作：前 3 周全空 → stagnantWeeks = 0。
        stubReview(checkIns = listOf(checkIn(exerciseId = 1L, epochDay = day(0), weightKg = 60f)))

        val review = reviewUseCase()()

        assertTrue("刚开始练的动作第一周不该被判停滞", review.training.stalled.isEmpty())
        assertTrue("上周没记录 → 也不该被当成「进步」", review.training.progressed.isEmpty())
    }

    // =================================================================
    // ③ 时间只能来自注入的 clock + timeZone
    // =================================================================

    @Test
    fun weekBoundary_followsTheInjectedTimeZone_notTheHost() = runTest {
        stubReview()
        // 2026-09-20T16:30Z：UTC 下仍是周日（本周 09-14），上海时间已是周一 09-21（本周 09-21）。
        val clock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-09-20T16:30:00Z")
        }
        val expectedShanghaiWeek =
            BuildWeeklyReviewUseCase.weekStartOf(LocalDate(2026, 9, 21).toEpochDays().toLong())

        assertEquals(weekStart, reviewUseCase(clock, TimeZone.UTC)().weekStartEpochDay)
        assertEquals(
            "同一个 Instant，换了时区必须换周（证明时间只来自注入的 clock + timeZone）",
            expectedShanghaiWeek,
            reviewUseCase(clock, TimeZone.of("Asia/Shanghai"))().weekStartEpochDay,
        )
    }

    @Test
    fun weekStartOf_isMondayAligned_includingNegativeEpochDays() {
        assertEquals(-3L, BuildWeeklyReviewUseCase.weekStartOf(0L))    // 1970-01-01 周四
        assertEquals(-3L, BuildWeeklyReviewUseCase.weekStartOf(-3L))   // 1969-12-29 周一
        assertEquals(4L, BuildWeeklyReviewUseCase.weekStartOf(10L))    // 1970-01-11 周日 → 周一 01-05
        assertEquals(weekStart, BuildWeeklyReviewUseCase.weekStartOf(LocalDate(2026, 9, 20).toEpochDays().toLong()))
    }

    // =================================================================
    // ④ JSON 合同
    // =================================================================

    private fun fullReview(): WeeklyReview = WeeklyReview(
        weekStartEpochDay = weekStart,
        weekEndEpochDay = weekStart + 6,
        training = TrainingReview(
            plannedDays = 3,
            completedDays = 3,
            totalVolumeKg = 12480f,
            totalSets = 36,
            avgRpe = 7.25f,
            progressed = listOf(
                ExerciseTrend(1L, "杠铃深蹲", previousWeightKg = 40f, latestWeightKg = 42.5f, stagnantWeeks = 0),
            ),
            stalled = listOf(
                ExerciseTrend(2L, "卧推", previousWeightKg = 30f, latestWeightKg = 30f, stagnantWeeks = 3),
            ),
        ),
        body = BodyReview(startWeightKg = 74.6f, latestWeightKg = 74.2f, sampleCount = 2),
        diet = DietReview(loggedDays = 2, avgKcal = 1150, avgProteinG = 55),
        days = (0..6).map { offset ->
            WeekDayDetail(
                dateEpochDay = weekStart + offset,
                items = if (offset == 1) {
                    listOf(
                        WeekItemDetail(
                            exerciseId = 1L,
                            exerciseName = "杠铃深蹲",
                            sets = 4,
                            reps = 8,
                            weightKg = 42.5f,
                            rpe = 6,
                            note = null,
                        ),
                    )
                } else {
                    emptyList()
                },
            )
        },
    )

    @Test
    fun jsonTopLevelKeys_areExactlyTheFrozenContract() = runTest {
        val json = exportUseCase()(fullReview()).json()

        assertEquals(
            "顶层字段名/顺序是接口，多一个少一个都会让下游 AI 读错",
            listOf("schema", "generatedAtEpochMillis", "profile", "week", "summary", "library"),
            json.keys.toList(),
        )
        assertEquals(
            listOf("from", "to", "days"),
            json["week"]!!.jsonObject.keys.toList(),
        )
        assertEquals(
            listOf(
                "attendance", "totalVolumeKg", "avgRpe", "weightDeltaKg",
                "dietAvgKcal", "dietAvgProteinG", "stalled", "progressed",
            ),
            json["summary"]!!.jsonObject.keys.toList(),
        )
        assertEquals(
            listOf(
                "gender", "age", "heightCm", "bodyFatPct", "goal", "goalWeightKg",
                "equipment", "injuryAreas", "injuryNote", "trainingDaysPerWeek",
            ),
            json["profile"]!!.jsonObject.keys.toList(),
        )
        assertFalse("streakDays 已按设计删除，不该复活", json["summary"]!!.jsonObject.containsKey("streakDays"))
    }

    @Test
    fun weekDates_areIsoAndCoverSevenDays() = runTest {
        val week = exportUseCase()(fullReview()).json()["week"]!!.jsonObject
        val days = week["days"]!!.jsonArray

        assertEquals("2026-09-14", week["from"]!!.jsonPrimitive.content)
        assertEquals("2026-09-20", week["to"]!!.jsonPrimitive.content)
        assertEquals(7, days.size)
        assertEquals(
            (0..6).map { (LocalDate.fromEpochDays((weekStart + it).toInt())).toString() },
            days.map { it.jsonObject["date"]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf("date", "done", "items"),
            days[0].jsonObject.keys.toList(),
        )
        assertEquals(
            listOf("exercise", "sets", "reps", "weightKg", "rpe", "note"),
            days[1].jsonObject["items"]!!.jsonArray[0].jsonObject.keys.toList(),
        )
    }

    @Test
    fun includeDetailsFalse_daysIsAnEmptyArray_butSummaryAndWeekRangeSurvive() = runTest {
        val json = exportUseCase()(fullReview(), includeDetails = false).json()

        assertTrue("省 token 模式：days 必须是空数组", json["week"]!!.jsonObject["days"]!!.jsonArray.isEmpty())
        assertEquals("2026-09-14", json["week"]!!.jsonObject["from"]!!.jsonPrimitive.content)
        assertEquals("3/3", json["summary"]!!.jsonObject["attendance"]!!.jsonPrimitive.content)
        assertEquals("12480.0", json["summary"]!!.jsonObject["totalVolumeKg"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyProfile_outputsExplicitNulls_andNeverOmitsFields() = runTest {
        val profile = exportUseCase(profile = UserProfile())(fullReview()).json()["profile"]!!.jsonObject

        for (key in listOf("gender", "age", "heightCm", "bodyFatPct", "goalWeightKg", "injuryNote")) {
            assertTrue("$key 必须存在且为 null（删字段会让下游分不清「没这项」和「这项没数据」）", profile[key] is JsonNull)
        }
        assertEquals("MAINTAIN", profile["goal"]!!.jsonPrimitive.content)
        assertTrue(profile["equipment"]!!.jsonArray.isEmpty())
        assertTrue(profile["injuryAreas"]!!.jsonArray.isEmpty())
        assertEquals(3, profile["trainingDaysPerWeek"]!!.jsonPrimitive.int)
    }

    @Test
    fun missingWeightAndRpe_areNullNotZero() = runTest {
        val review = fullReview().copy(
            body = BodyReview(startWeightKg = null, latestWeightKg = null),
            training = fullReview().training.copy(avgRpe = null),
        )
        val summary = exportUseCase()(review).json()["summary"]!!.jsonObject

        assertTrue(summary["weightDeltaKg"] is JsonNull)
        assertTrue(summary["avgRpe"] is JsonNull)
    }

    @Test
    fun singleWeightInWeek_exportedWeightDeltaIsNullNotZero() = runTest {
        val review = fullReview().copy(body = BodyReview(startWeightKg = 74.6f, latestWeightKg = 74.6f, sampleCount = 1))
        val summary = exportUseCase()(review).json()["summary"]!!.jsonObject

        assertTrue("一条体重 → delta 必须 null（导出口径与界面一致）", summary["weightDeltaKg"] is JsonNull)
    }

    @Test
    fun floatNoise_isRoundedToOneDecimal() = runTest {
        // 74.2f - 74.6f 在 Float 下是 -0.40000153，原样发给 AI 会让人以为数据坏了。
        val raw = 74.2f - 74.6f
        assertNotEquals("先证明这个减法确实带噪声（否则本用例等于没测）", -0.4f, raw)

        val review = fullReview().copy(
            body = BodyReview(startWeightKg = 74.6f, latestWeightKg = 74.2f, sampleCount = 2),
            training = fullReview().training.copy(avgRpe = 7.3f),
        )
        val summary = exportUseCase()(review).json()["summary"]!!.jsonObject

        assertEquals("-0.4", summary["weightDeltaKg"]!!.jsonPrimitive.content)
        assertEquals("7.3", summary["avgRpe"]!!.jsonPrimitive.content)
        assertEquals("12480.0", summary["totalVolumeKg"]!!.jsonPrimitive.content)
    }

    @Test
    fun avgRpe_isRoundedAtTheExportBoundary_too() = runTest {
        // 复核报告 F-3 的**回归版**（原文断言"原样透传 7.3333335"。
        //
        // 原缺陷：导出层对 totalVolumeKg / weightDeltaKg 都做了"圆到 1 位小数"，唯独 avgRpe
        // 原样透传。本期因为 avgRpe 只来自 BuildWeeklyReviewUseCase（内部已圆过）看不出问题，
        // 但 WeeklyReview 是**公开 API**，任何其它来源传进来 7.25 / 7.3333335 都会原样进 JSON，
        // 与"summary 一律 1 位小数"的契约不一致。修复：`avgRpe = review.training.avgRpe?.roundTo1()`。
        val review = fullReview().copy(training = fullReview().training.copy(avgRpe = 7.3333335f))
        val summary = exportUseCase()(review).json()["summary"]!!.jsonObject

        assertEquals(
            "导出层必须把 avgRpe 一并圆到 1 位小数（与 totalVolumeKg / weightDeltaKg 同口径）",
            "7.3",
            summary["avgRpe"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun json_containsNoSecretMaterial_andKeepsChineseReadable() = runTest {
        val profile = UserProfile(
            gender = Gender.MALE,
            age = 30,
            heightCm = 178,
            bodyFatPct = 22f,
            goal = Goal.BULK,
            goalWeightKg = 80f,
            equipment = setOf(Equipment.DUMBBELL, Equipment.BARBELL),
            injuryAreas = setOf(InjuryArea.KNEE),
            injuryNote = "深蹲到底右膝有点顶",
            trainingDaysPerWeek = 5,
        )
        val text = exportUseCase(profile = profile, library = exercises)(fullReview())
        val lowered = text.lowercase()

        assertFalse("不允许出现 API Key", lowered.contains("apikey"))
        assertFalse("不允许出现 sk- 前缀", lowered.contains("sk-"))
        assertFalse("不允许出现 authorization", lowered.contains("authorization"))
        assertFalse("不允许出现设备标识", lowered.contains("deviceid"))
        assertFalse("不允许出现 token 字段", lowered.contains("\"token\""))
        assertTrue("中文不该被转义（用户要直接复制给人看）", text.contains("杠铃深蹲"))
        assertTrue("应是格式化过的多行 JSON", text.contains("\n"))
    }

    @Test
    fun profile_trainingDaysPerWeekIsCarriedIntoThePackage() = runTest {
        // P1 与 P2 的三处差异之一：AI 必须知道用户每周练几天。
        for (days in 3..6) {
            val profile = exportUseCase(profile = UserProfile(trainingDaysPerWeek = days))(fullReview())
                .json()["profile"]!!.jsonObject
            assertEquals("trainingDaysPerWeek=$days 应原样进包", days, profile["trainingDaysPerWeek"]!!.jsonPrimitive.int)
        }
        // 越界值必须钳制后再导出（不能把 99 这种脏数据发给 AI）。
        val clamped = exportUseCase(profile = UserProfile(trainingDaysPerWeek = 99))(fullReview())
            .json()["profile"]!!.jsonObject
        assertEquals(6, clamped["trainingDaysPerWeek"]!!.jsonPrimitive.int)
    }

    @Test
    fun library_isTrimmedDedupedSortedAndDropsBlankNames() = runTest {
        val library = listOf(
            exercise(1L, " 卧推 "),
            exercise(2L, "卧推"),
            exercise(3L, "杠铃深蹲"),
            exercise(4L, "   ", ExerciseCategory.CARDIO),
        )
        val items = exportUseCase(library = library)(fullReview()).json()["library"]!!.jsonArray
            .map { it.jsonObject }

        assertEquals(2, items.size)
        assertEquals(listOf("卧推", "杠铃深蹲"), items.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("name", "category", "muscleGroups"), items[0].keys.toList())
    }
}
