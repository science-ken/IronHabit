package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.BodyReview
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
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.SettingsRepository
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExportWeekPackageUseCase] 单测（P2 · 数据包导出）。
 *
 * 这一组用例锁死的是**对外合同**：字段名、嵌套、日期格式、`null` 语义。
 * 因为这份 JSON 是要交给"别人的 AI"读的 —— 字段名一改，下游就全断，
 * 所以这里的断言直接按 JSON 路径取值（而不是反序列化到 DTO 上），
 * 保证"字段名写错了"这种问题一定被抓到。
 */
class ExportWeekPackageUseCaseTest {

    private val fixedInstant: Instant = Instant.parse("2026-09-16T10:00:00Z")
    private val fixedClock = object : Clock {
        override fun now(): Instant = fixedInstant
    }

    private val weekStart: Long = LocalDate(2026, 9, 14).toEpochDays().toLong()

    private val settingsRepository = mockk<SettingsRepository>()
    private val exerciseRepository = mockk<ExerciseRepository>()

    private fun useCase(): ExportWeekPackageUseCase = ExportWeekPackageUseCase(
        settingsRepository = settingsRepository,
        exerciseRepository = exerciseRepository,
        clock = fixedClock,
        timeZone = TimeZone.UTC,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun stub(
        profile: UserProfile = UserProfile(),
        library: List<Exercise> = emptyList(),
    ) {
        every { settingsRepository.profile() } returns flowOf(profile)
        every { exerciseRepository.observeActive() } returns flowOf(library)
    }

    private fun exercise(id: Long, name: String, category: ExerciseCategory = ExerciseCategory.STRENGTH): Exercise =
        Exercise(
            id = id,
            name = name,
            category = category,
            muscleGroups = listOf("腿部"),
            isActive = true,
        )

    /** 一份"有数据"的周复盘：3 天打卡、有进步也有停滞、有体重、有饮食。 */
    private fun review(): WeeklyReview = WeeklyReview(
        weekStartEpochDay = weekStart,
        weekEndEpochDay = weekStart + 6,
        training = TrainingReview(
            plannedDays = 3,
            completedDays = 3,
            totalVolumeKg = 12480f,
            totalSets = 36,
            avgRpe = 7.2f,
            progressed = listOf(
                ExerciseTrend(
                    exerciseId = 1L,
                    exerciseName = "杠铃深蹲",
                    previousWeightKg = 40f,
                    latestWeightKg = 42.5f,
                    stagnantWeeks = 0,
                ),
            ),
            stalled = listOf(
                ExerciseTrend(
                    exerciseId = 2L,
                    exerciseName = "卧推",
                    previousWeightKg = 30f,
                    latestWeightKg = 30f,
                    stagnantWeeks = 3,
                ),
            ),
        ),
        body = BodyReview(startWeightKg = 74.6f, latestWeightKg = 74.2f, sampleCount = 2),
        diet = DietReview(loggedDays = 2, avgKcal = 1150, avgProteinG = 55),
        days = (0..6).map { offset ->
            if (offset == 1) {
                WeekDayDetail(
                    dateEpochDay = weekStart + offset,
                    items = listOf(
                        WeekItemDetail(
                            exerciseId = 1L,
                            exerciseName = "杠铃深蹲",
                            sets = 4,
                            reps = 8,
                            weightKg = 42.5f,
                            rpe = 6,
                            note = null,
                        ),
                    ),
                )
            } else {
                WeekDayDetail(dateEpochDay = weekStart + offset)
            }
        },
        notes = emptyList(),
    )

    private fun String.toJson() = Json.parseToJsonElement(this).jsonObject

    private suspend fun exported(
        profile: UserProfile = UserProfile(),
        library: List<Exercise> = emptyList(),
        includeDetails: Boolean = true,
    ): String {
        stub(profile = profile, library = library)
        return useCase()(review(), includeDetails)
    }

    @Test
    fun schemaAndTimestamp_arePinned() = runTest {
        val json = exported().toJson()

        assertEquals("ironhabit-week-package/v1", json["schema"]!!.jsonPrimitive.content)
        assertEquals(fixedInstant.toEpochMilliseconds(), json["generatedAtEpochMillis"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun weekDates_areIsoFormatted_andDaysCoverWholeWeek() = runTest {
        val week = exported().toJson()["week"]!!.jsonObject
        val days = week["days"]!!.jsonArray

        assertEquals("2026-09-14", week["from"]!!.jsonPrimitive.content)
        assertEquals("2026-09-20", week["to"]!!.jsonPrimitive.content)
        assertEquals("一周 7 天都要在", 7, days.size)
        assertEquals("2026-09-14", days[0].jsonObject["date"]!!.jsonPrimitive.content)
        assertEquals("2026-09-20", days[6].jsonObject["date"]!!.jsonPrimitive.content)
    }

    @Test
    fun dayItems_carryExerciseNameSetsRepsWeightAndRpe() = runTest {
        val days = exported().toJson()["week"]!!.jsonObject["days"]!!.jsonArray
        val tuesday = days[1].jsonObject

        assertEquals(true, tuesday["done"]!!.jsonPrimitive.content.toBoolean())
        val item = tuesday["items"]!!.jsonArray.single().jsonObject
        assertEquals("杠铃深蹲", item["exercise"]!!.jsonPrimitive.content)
        assertEquals(4, item["sets"]!!.jsonPrimitive.int)
        assertEquals(8, item["reps"]!!.jsonPrimitive.int)
        assertEquals("42.5", item["weightKg"]!!.jsonPrimitive.content)
        assertEquals(6, item["rpe"]!!.jsonPrimitive.int)
        assertTrue("没备注要输出 null", item["note"] is JsonNull)

        val monday = days[0].jsonObject
        assertEquals(false, monday["done"]!!.jsonPrimitive.content.toBoolean())
        assertTrue("没打卡的天 items 是空数组", monday["items"]!!.jsonArray.isEmpty())
    }

    @Test
    fun includeDetailsFalse_dropsDayDetailsButKeepsSummary() = runTest {
        val json = exported(includeDetails = false).toJson()

        assertTrue("省 token 模式下 days 为空数组", json["week"]!!.jsonObject["days"]!!.jsonArray.isEmpty())
        assertEquals(
            "汇总必须照旧保留",
            "3/3",
            json["summary"]!!.jsonObject["attendance"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun summary_matchesTheFrozenContract() = runTest {
        val summary = exported().toJson()["summary"]!!.jsonObject

        assertEquals("3/3", summary["attendance"]!!.jsonPrimitive.content)
        assertEquals("12480.0", summary["totalVolumeKg"]!!.jsonPrimitive.content)
        assertEquals("7.2", summary["avgRpe"]!!.jsonPrimitive.content)
        assertEquals("-0.4", summary["weightDeltaKg"]!!.jsonPrimitive.content)
        assertEquals(1150, summary["dietAvgKcal"]!!.jsonPrimitive.int)
        assertEquals(55, summary["dietAvgProteinG"]!!.jsonPrimitive.int)

        val stalled = summary["stalled"]!!.jsonArray.single().jsonObject
        assertEquals("卧推", stalled["exercise"]!!.jsonPrimitive.content)
        assertEquals(3, stalled["stagnantWeeks"]!!.jsonPrimitive.int)

        val progressed = summary["progressed"]!!.jsonArray.single().jsonObject
        assertEquals("杠铃深蹲", progressed["exercise"]!!.jsonPrimitive.content)
        assertEquals("40.0", progressed["previousWeightKg"]!!.jsonPrimitive.content)
        assertEquals("42.5", progressed["latestWeightKg"]!!.jsonPrimitive.content)
    }

    @Test
    fun profile_isFullyIncluded_includingTrainingDays() = runTest {
        val profile = UserProfile(
            gender = Gender.MALE,
            age = 30,
            heightCm = 178,
            bodyFatPct = 22f,
            goal = Goal.BULK,
            goalWeightKg = 80f,
            equipment = setOf(Equipment.DUMBBELL, Equipment.PULLUP_BAR),
            injuryAreas = setOf(InjuryArea.KNEE),
            injuryNote = "深蹲到底右膝有点顶",
            trainingDaysPerWeek = 5,
        )

        val json = exported(profile = profile).toJson()["profile"]!!.jsonObject

        assertEquals("MALE", json["gender"]!!.jsonPrimitive.content)
        assertEquals(30, json["age"]!!.jsonPrimitive.int)
        assertEquals(178, json["heightCm"]!!.jsonPrimitive.int)
        assertEquals("22.0", json["bodyFatPct"]!!.jsonPrimitive.content)
        assertEquals("BULK", json["goal"]!!.jsonPrimitive.content)
        assertEquals("80.0", json["goalWeightKg"]!!.jsonPrimitive.content)
        assertEquals(
            "器械集合输出为枚举 name 数组",
            listOf("DUMBBELL", "PULLUP_BAR"),
            json["equipment"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("KNEE"),
            json["injuryAreas"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("深蹲到底右膝有点顶", json["injuryNote"]!!.jsonPrimitive.content)
        assertEquals("P1 新增：AI 必须知道每周几天", 5, json["trainingDaysPerWeek"]!!.jsonPrimitive.int)
    }

    @Test
    fun emptyProfile_outputsNullsInsteadOfOmittingFields() = runTest {
        val json = exported(profile = UserProfile()).toJson()["profile"]!!.jsonObject

        assertTrue("字段必须存在，值为 null", json.containsKey("gender"))
        assertTrue(json["gender"] is JsonNull)
        assertTrue(json["age"] is JsonNull)
        assertTrue(json["heightCm"] is JsonNull)
        assertTrue(json["bodyFatPct"] is JsonNull)
        assertTrue(json["goalWeightKg"] is JsonNull)
        assertTrue(json["injuryNote"] is JsonNull)
        assertEquals("目标有默认值，不是 null", "MAINTAIN", json["goal"]!!.jsonPrimitive.content)
        assertTrue("未选器械 → 空数组（不是 null）", json["equipment"]!!.jsonArray.isEmpty())
        assertEquals("默认每周 3 天", 3, json["trainingDaysPerWeek"]!!.jsonPrimitive.int)
    }

    @Test
    fun missingWeightOrRpe_outputsNull_notZero() = runTest {
        stub(profile = UserProfile())
        val reviewWithoutBody = review().copy(
            body = BodyReview(startWeightKg = null, latestWeightKg = null),
            training = review().training.copy(avgRpe = null),
        )

        val summary = useCase()(reviewWithoutBody).toJson()["summary"]!!.jsonObject

        assertTrue("没记体重 → null（不能用 0 冒充「没变化」）", summary["weightDeltaKg"] is JsonNull)
        assertTrue("没评 RPE → null", summary["avgRpe"] is JsonNull)
    }

    @Test
    fun library_isSortedDedupedAndTrimmed() = runTest {
        val library = listOf(
            exercise(1L, " 卧推 "),
            exercise(2L, "卧推"),
            exercise(3L, "杠铃深蹲"),
            exercise(4L, "", ExerciseCategory.CARDIO),
        )

        val items = exported(library = library).toJson()["library"]!!.jsonArray.map { it.jsonObject }

        assertEquals("空名字要丢掉，同名要合并", 2, items.size)
        assertEquals(listOf("卧推", "杠铃深蹲"), items.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("STRENGTH", items[0]["category"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("腿部"),
            items[0]["muscleGroups"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun jsonIsValid_andHasNoSecretMaterial() = runTest {
        val json = exported(
            profile = UserProfile(gender = Gender.MALE, age = 30),
            library = listOf(exercise(1L, "杠铃深蹲")),
        )

        // 能被解析 = 格式合法（prettyPrint 也不会破坏合法性）
        val parsed = json.toJson()
        assertEquals("ironhabit-week-package/v1", parsed["schema"]!!.jsonPrimitive.content)

        val lowered = json.lowercase()
        assertFalse("数据包里绝不允许出现 API Key", lowered.contains("apikey"))
        assertFalse(lowered.contains("sk-"))
        assertFalse(lowered.contains("authorization"))
        assertFalse("也不允许出现设备/账号标识", lowered.contains("deviceid"))
        assertTrue("应该是格式化过的多行 JSON（方便用户直接看）", json.contains("\n"))
    }
}
