package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ExternalPlanSchema
import com.ironhabit.app.domain.model.BodyReview
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.DietReview
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.FoodRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
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
    private val foodRepository: FoodRepository = mockk(relaxed = true)
    private val bodyMetricRepository: BodyMetricRepository = mockk(relaxed = true)
    private val trainingDayResolver: TrainingDayResolver = mockk(relaxed = true)
    private val exportWeekPackage: ExportWeekPackageUseCase = mockk()

    private fun useCase(): BuildExternalCoachPromptUseCase = BuildExternalCoachPromptUseCase(
        settingsRepository = settingsRepository,
        planRepository = planRepository,
        exerciseRepository = exerciseRepository,
        foodRepository = foodRepository,
        bodyMetricRepository = bodyMetricRepository,
        trainingDayResolver = trainingDayResolver,
        exportWeekPackage = exportWeekPackage,
    )

    private fun stub(
        profile: UserProfile = UserProfile(),
        weekRows: List<WeekPlan> = emptyList(),
        foods: List<Food> = listOf(builtInFood(1L, "米饭（蒸）"), builtInFood(2L, "鸡胸肉")),
        trainingDays: List<Boolean> = List(7) { true },
    ) {
        every { settingsRepository.profile() } returns flowOf(profile)
        every { exerciseRepository.observeActive() } returns flowOf(
            listOf(exercise(1L, "杠铃深蹲"), exercise(2L, "卧推")),
        )
        every { foodRepository.observeAll() } returns flowOf(foods)
        // 体重必须显式 stub 成 null：relaxed mock 对可空返回会给出一个非空链式对象，
        // 那会让模板里凭空出现一个假体重。
        coEvery { bodyMetricRepository.latest(any()) } returns null
        coEvery { trainingDayResolver(any()) } returnsMany trainingDays
        coEvery { planRepository.getRowsForWeek(any()) } returns weekRows
        coEvery { exportWeekPackage(any(), any(), any()) } returns packageJson
    }

    private fun builtInFood(id: Long, name: String) = Food(
        id = id,
        name = name,
        kcalPer100g = 100,
        proteinPer100g = 10.0,
        carbsPer100g = 10.0,
        fatPer100g = 1.0,
    )

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
    fun template_embedsTheCompactPackage_becausePastingLosesTheTail() = runTest {
        stub()

        useCase()(review, weekStart)

        // 缩进把字节数放大一倍多，而整段被截断时丢的是**尾部** —— library 恰好排在最后。
        // 模型因此回一句"没收到动作库"，用户白问一次（真机实测到的就是这个）。
        coVerify(exactly = 1) { exportWeekPackage(any(), true, false) }
    }

    @Test
    fun template_onlyNamesFieldsThePackageActuallyCarries() = runTest {
        stub()

        val text = useCase()(review, weekStart)

        assertFalse(
            "数据包里没有 history 数组（那是内置 DeepSeek 载荷才有的字段）：提了它，模型只会回「没收到 history」",
            text.contains("history"),
        )
        assertTrue("动作名白名单必须点名 library", text.contains("library"))
        assertTrue("加重判据要指向真有的字段", text.contains("summary.progressed"))
    }

    @Test
    fun template_tellsTheModelToSaySoInsteadOfReturningAnEmptyShell() = runTest {
        stub()

        val text = useCase()(review, weekStart)

        // 真机拿到的那份输出就是"三个空天 + 一句解释"：宁可它说没收到，也别编一堆假动作名。
        assertTrue(text.contains("没收到 library"))
    }

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
    fun template_asksForAPerItemReason_andTheExampleCarriesOne() = runTest {
        stub()

        val text = useCase()(review, weekStart)

        assertTrue("不点名要，模型不会主动给每条动作写理由", text.contains("\"reason\""))
        assertTrue(
            "示例里也得带一个：模型是照着示例的形状输出的，示例没有它就会省略",
            text.contains("上周做满且 RPE 6，小幅加重"),
        )
    }

    @Test
    fun template_tellsTheModelHowToDeclareANewExercise_withVocabularyGeneratedFromCode() = runTest {
        stub()

        val text = useCase()(review, weekStart)

        assertTrue(text.contains("newExercises"))
        // 分类与肌群清单都从代码现生成：手抄的话，加一个成员就会出现
        // "模型写了个合法值、App 说认不出"那种查不出头的分歧。
        assertTrue(text.contains("BODYWEIGHT/STRENGTH/CARDIO/CUSTOM"))
        assertTrue(text.contains("腿部"))
        assertTrue(text.contains("臀腿"))
        assertTrue("不声明就丢这条，必须写在模板里", text.contains("会被 App 直接丢掉"))
        assertFalse("占位符一个都不许残留", text.contains("{{"))
    }

    @Test
    fun template_tellsTheModelNotToTouchMeasurements() = runTest {
        stub()

        val text = useCase()(review, weekStart)

        assertTrue(
            "实测值永远不收：不写死这一句，模型就会顺手改身高体重",
            text.contains("不收 AI 填的这一项"),
        )
    }

    @Test
    fun template_listsTheLegalProfileValuesGeneratedFromTheEnums() = runTest {
        stub()

        val text = useCase()(review, weekStart)

        // 合法值从枚举现生成：手抄清单的话，加一个 Goal 成员就会出现
        // "模型写了个合法值、App 说认不出"那种查不出头的分歧。
        assertTrue(text.contains("CUT/BULK/RECOMP/SHAPE/MAINTAIN"))
        assertTrue(text.contains("BARBELL"))
        assertTrue(text.contains("LOWER_BACK"))
        assertTrue(text.contains("trainingDaysPerWeek"))
        assertFalse("占位符一个都不许残留", text.contains("{{"))
    }

    // ---------------- 饮食侧（v2）----------------

    @Test
    fun template_forbidsTheModelToReturnNutritionNumbers_andAsksForGramsOnly() = runTest {
        // 这一句是 R1 在**出站方向**的落点：数字只能从我发过去的那份食物库算出来。
        // 不收这一句，模型就会回 "kcal":1800 这种东西，而预览页那句「数字本地算」成假话。
        stub()

        val text = useCase()(review, weekStart)

        assertTrue("明说不要写营养数字", text.contains("热量和蛋白质数字一律不要写"))
        assertTrue("份量只要克数，不要碗/勺（24 种单位名模型抄不准）", text.contains("grams 必须是整数克数"))
        assertTrue("没写到的餐次要保住：不写这句，模型会把四餐硬凑满", text.contains("没写到的餐次 App 原样保留"))
        assertTrue(text.contains("BREAKFAST/LUNCH/SNACK/DINNER"))
        assertFalse("占位符一个都不许残留", text.contains("{{"))
    }

    @Test
    fun template_putsFoodsAndTargetsAheadOfThePackage_becausePastingLosesTheTail() = runTest {
        stub()

        val text = useCase()(review, weekStart)

        // 数据包尾部已经有 library；食物清单再排到它后面，截断时两个清单一起丢，
        // 表现是"模型说没收到食物清单"，而用户看不出模板里其实发了。
        assertTrue(text.indexOf("FOODS 我的食物库") < text.indexOf(packageJson))
        assertTrue(text.indexOf("每天的目标") < text.indexOf(packageJson))
        assertTrue("清单只用库里的真名", text.contains("米饭（蒸）") && text.contains("鸡胸肉"))
    }

    @Test
    fun template_givesOneTargetLinePerDay_andSeparatesTrainingFromRestDays() = runTest {
        stub(trainingDays = listOf(true, true, false, false, false, false, false))

        val text = useCase()(review, weekStart)
        val block: String = text.substringAfter("每天的目标").substringBefore("FOODS 我的食物库")
        val lines: List<String> = block.lines().filter { it.startsWith("周") }

        assertEquals("七天七个目标，一天都不能少", 7, lines.size)
        assertEquals(2, lines.count { it.contains("（训练日）") })
        assertEquals(5, lines.count { it.contains("（休息日）") })
        val kcals: List<String> = lines.map { it.substringAfter("：").substringBefore(" kcal") }
        assertEquals(
            "训练日 1.55 / 休息日 1.375，系数不同 → 必须给两个不同的数，给一个周均值就是骗它",
            2,
            kcals.distinct().size,
        )
    }

    @Test
    fun template_statesAvoidedCategories_andSaysSoExplicitlyWhenThereAreNone() = runTest {
        stub(profile = UserProfile(dietaryAvoid = setOf(DietRestriction.SEAFOOD, DietRestriction.PEANUT)))
        val withAvoid = useCase()(review, weekStart)

        stub(profile = UserProfile())
        val withoutAvoid = useCase()(review, weekStart)

        assertTrue(withAvoid.contains("SEAFOOD/PEANUT") || withAvoid.contains("PEANUT/SEAFOOD"))
        assertTrue(
            "没忌口也要说一句，不能留一个空冒号让模型自己猜",
            withoutAvoid.contains("（档案里没有登记任何忌口）"),
        )
    }

    @Test
    fun template_offersNewFoodDeclarationWithoutAdmittingItsNumbersIntoTheMath() = runTest {
        // 建库门票取消后必须说清"你给的数值只是预填"，否则模型会以为写了就直接生效。
        stub()

        val text = useCase()(review, weekStart)

        assertTrue(text.contains("newFoods"))
        assertTrue(text.contains("预填在我那张确认表上"))
        assertTrue(text.contains("库里没有的食物"))
    }
}
