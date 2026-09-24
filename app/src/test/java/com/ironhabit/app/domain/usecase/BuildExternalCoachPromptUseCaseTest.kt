package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ExternalPlanSchema
import com.ironhabit.app.domain.model.BodyReview
import com.ironhabit.app.domain.model.DietReview
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BuildExternalCoachPromptUseCase] 单测 —— 钉的是"这段文本真能拿去问出可导入的文档"。
 *
 * 数据包本身由 `ExportWeekPackageUseCaseTest` 负责，这里 mock 掉它，只测**拼装**：
 * 占位符有没有全替换干净、训练日数是不是来自档案、目标周现状有没有写进去。
 * 占位符漏一个，用户带去的就是一句没写完的话，而模型会**照着自己猜**补完 —— 那是最难查的脏数据。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuildExternalCoachPromptUseCaseTest {

    private val weekStart: Long = LocalDate(2026, 9, 21).toEpochDays().toLong()
    private val packageJson: String = """{"package":"SENTINEL"}"""

    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val planRepository: PlanRepository = mockk(relaxed = true)
    private val exerciseRepository: ExerciseRepository = mockk(relaxed = true)
    private val exportWeekPackage: ExportWeekPackageUseCase = mockk()

    private fun useCase(): BuildExternalCoachPromptUseCase = BuildExternalCoachPromptUseCase(
        settingsRepository = settingsRepository,
        planRepository = planRepository,
        exerciseRepository = exerciseRepository,
        exportWeekPackage = exportWeekPackage,
    )

    private fun stub(profile: UserProfile = UserProfile(), weekRows: List<WeekPlan> = emptyList()) {
        every { settingsRepository.profile() } returns flowOf(profile)
        every { exerciseRepository.observeActive() } returns flowOf(
            listOf(exercise(1L, "杠铃深蹲"), exercise(2L, "卧推")),
        )
        coEvery { planRepository.getRowsForWeek(any()) } returns weekRows
        coEvery { exportWeekPackage(any(), any()) } returns packageJson
    }

    private fun exercise(id: Long, name: String) = Exercise(
        id = id,
        name = name,
        category = ExerciseCategory.STRENGTH,
        muscleGroups = listOf("腿部"),
        isActive = true,
    )

    private val review: WeeklyReview = WeeklyReview(
        weekStartEpochDay = weekStart,
        weekEndEpochDay = weekStart + 6,
        training = TrainingReview(
            plannedDays = 3,
            completedDays = 2,
            totalVolumeKg = 0f,
            totalSets = 0,
            avgRpe = null,
            progressed = emptyList(),
            stalled = emptyList(),
        ),
        body = BodyReview(startWeightKg = null, latestWeightKg = null, sampleCount = 0),
        diet = DietReview(loggedDays = 0, avgKcal = null, avgProteinG = null),
    )

    @Test
    fun template_replacesEveryPlaceholder() = runTest {
        stub()

        val text = useCase()(review, weekStart)

        assertFalse(
            "漏一个 {{...}} 等于给用户一句没写完的指令",
            text.contains("{{"),
        )
        assertTrue("回程合同必须写进模板", text.contains(ExternalPlanSchema.SCHEMA))
        assertTrue("数据包必须原样嵌入", text.contains(packageJson))
    }

    @Test
    fun template_trainingDaysComeFromProfile_andAreClamped() = runTest {
        stub(profile = UserProfile(trainingDaysPerWeek = 5))
        val fiveDays = useCase()(review, weekStart)
        assertTrue(fiveDays.contains("只安排 5 个训练日"))

        stub(profile = UserProfile(trainingDaysPerWeek = 99))
        val clamped = useCase()(review, weekStart)
        assertTrue("档案合法域 3..6：越界值钳过再进模板，和内置生成同一口径", clamped.contains("只安排 6 个训练日"))
    }

    @Test
    fun template_showsAbsoluteDateRangeOfTargetWeek() = runTest {
        stub()

        val text = useCase()(review, weekStart)

        // 模型没有"今天"的概念，只说"下周"它会挑一个随机的周。
        assertTrue(text.contains("2026-09-21 ~ 2026-09-27"))
    }

    @Test
    fun template_listsWhatTheTargetWeekAlreadyHas() = runTest {
        stub(
            weekRows = listOf(
                WeekPlan(
                    id = 1L,
                    exerciseId = 1L,
                    dayOfWeek = 1,
                    targetSets = 4,
                    targetReps = 8,
                    targetWeightKg = 80f,
                    isActive = true,
                ),
            ),
        )

        val text = useCase()(review, weekStart)

        assertTrue("已有的动作要按名字列出来", text.contains("杠铃深蹲 4组×8次×80.0kg"))
        assertTrue(
            "没有专属行的天必须说「沿用模板」，否则模型读成「这天空着，随便排」",
            text.contains("沿用「每周相同」模板"),
        )
    }

    @Test
    fun template_emptyWeekSaysItOutLoud() = runTest {
        stub(weekRows = emptyList())

        val text = useCase()(review, weekStart)

        assertTrue(
            "整周空的时候不能只给一个空段落 —— 模型会以为缺数据而自己编",
            text.contains("还没有任何已排好的训练"),
        )
    }

    @Test
    fun template_softDeletedRowsAreNotListedAsExisting() = runTest {
        stub(
            weekRows = listOf(
                WeekPlan(id = 2L, exerciseId = 2L, dayOfWeek = 3, isActive = false),
            ),
        )

        val text = useCase()(review, weekStart)

        assertEquals(
            "软删掉的动作不算「已排」：它不该出现在现状摘要里，也不该被「别删」这句话保护",
            false,
            text.contains("卧推 3组×12次"),
        )
    }

    @Test
    fun template_tellsTheModelNotToTouchMeasurements() = runTest {
        stub()

        val text = useCase()(review, weekStart)

        assertTrue(
            "本版只导计划：模板必须挡住模型输出档案改动，否则用户以为改了、其实没改",
            text.contains("不要输出、也不要建议修改任何身体测量数据"),
        )
    }
}
