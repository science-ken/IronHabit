package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ExternalDocRefusal
import com.ironhabit.app.domain.ai.external.ExternalPlanNote
import com.ironhabit.app.domain.ai.external.ExternalPlanSchema
import com.ironhabit.app.domain.ai.external.ImportSection
import com.ironhabit.app.domain.ai.external.ProfileFieldDiff
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.FoodRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImportExternalPlanUseCase] 单测 —— 钉的是**外部来源特有**的那几条口径。
 *
 * 投影规则本身已在 [PlanDraftProjectorTest] 覆盖，这里只测"接线"接对没有：
 * 尤其 `retireStaleRows = false` 这一条 —— 接错的话，用户从外部 AI 问回来的计划
 * 在采纳那天之后，会把他本周没被那份文档提到的旧计划全部停用，而且界面上没有任何痕迹。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportExternalPlanUseCaseTest {

    private val targetWeek: Long = 20_724L

    private val exerciseRepository: ExerciseRepository = mockk(relaxed = true)
    private val foodRepository: FoodRepository = mockk(relaxed = true)
    private val mealRepository: MealRepository = mockk(relaxed = true)
    private val planRepository: PlanRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)

    private fun useCase(): ImportExternalPlanUseCase = ImportExternalPlanUseCase(
        planRepository = planRepository,
        exerciseRepository = exerciseRepository,
        foodRepository = foodRepository,
        mealRepository = mealRepository,
        settingsRepository = settingsRepository,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun stub(
        library: List<Exercise>,
        weekRows: List<WeekPlan> = emptyList(),
        templateRows: List<WeekPlan> = emptyList(),
        profile: UserProfile = UserProfile(trainingDaysPerWeek = 3),
        foods: List<Food> = emptyList(),
    ) {
        everyLibrary(library)
        // 食物库必须显式 stub：relaxed mock 对 `Flow` 返回的是"什么都不发"的空流，
        // 解析器里 `.first()` 会当场 NoSuchElementException。
        every { foodRepository.observeAll() } returns flowOf(foods)
        coEvery { mealRepository.getMealsIncludingInactive(any()) } returns emptyList()
        coEvery { planRepository.getRowsForWeek(any()) } returns weekRows
        coEvery { planRepository.getRepeatRows() } returns templateRows
        every { settingsRepository.profile() } returns flowOf(profile)
    }

    private fun everyLibrary(library: List<Exercise>) {
        coEvery { exerciseRepository.getAll() } returns library
    }

    private fun exercise(id: Long, name: String) = Exercise(
        id = id,
        name = name,
        category = ExerciseCategory.STRENGTH,
        muscleGroups = listOf("腿部"),
        isActive = true,
    )

    private val library: List<Exercise> = listOf(
        exercise(1L, "杠铃深蹲"),
        exercise(2L, "卧推"),
    )

    private fun document(items: String, day: Int = 1): String =
        """{"schema":"${ExternalPlanSchema.SCHEMA}","days":[{"dayOfWeek":$day,"focus":"FULL_BODY","items":[$items]}]}"""

    private val twoItems: String =
        """{"exercise":"杠铃深蹲","targetSets":4,"targetReps":8,"targetWeightKg":80.0},""" +
            """{"exercise":"卧推","targetSets":3,"targetReps":10,"targetWeightKg":60.0}"""

    private fun row(
        id: Long,
        day: Int,
        exerciseId: Long,
        isUserEdited: Boolean = false,
        weekStart: Long = targetWeek,
    ) = WeekPlan(
        id = id,
        exerciseId = exerciseId,
        dayOfWeek = day,
        isActive = true,
        isUserEdited = isUserEdited,
        weekStartEpochDay = weekStart,
    )

    // ---------------- 成功路 ----------------

    @Test
    fun import_readyPreview_carriesTargetWeekAndExternalSource() = runTest {
        stub(library)

        val result = useCase()(document(twoItems), targetWeek)

        val ready = result as ExternalPlanImport.Ready
        assertEquals(targetWeek, ready.preview.weekStartEpochDay)
        assertEquals(AdviceSource.EXTERNAL_AI_IMPORT, ready.preview.source)
        assertEquals(
            "草案一条不少地进预览页（没有保护规则要挡的东西）",
            listOf(1L, 2L),
            ready.preview.draftsByDay.getValue(1).map { it.exerciseId },
        )
        assertTrue(ready.preview.allDrafts.all { it.weekStartEpochDay == targetWeek })
    }

    @Test
    fun import_neverHandsOverRowsToRetire() = runTest {
        // 本周已有两条**生成行**（非手改）。内置生成会把"本次没再列出"的当成陈旧行停用，
        // 外部导入绝不这样做。
        stub(
            library = library,
            weekRows = listOf(row(id = 91L, day = 3, exerciseId = 1L)),
        )
        val onlyMonday = """{"exercise":"卧推","targetSets":3,"targetReps":10}"""

        val ready = useCase()(document(onlyMonday, day = 1), targetWeek) as ExternalPlanImport.Ready

        assertTrue(
            "回收快照必须为空 → commit 无行可停用 → 周三那条旧计划原地不动",
            ready.preview.weekRowsForRetirement.isEmpty(),
        )
        assertEquals(
            "但挡手改槽位用的行还是要读到的（这里没有手改行，所以只是不回收）",
            listOf(2L),
            ready.preview.draftsByDay.getValue(1).map { it.exerciseId },
        )
    }

    @Test
    fun import_ready_alsoCarriesTheProfileDiffsAgainstTheCurrentProfile() = runTest {
        stub(library, profile = UserProfile(trainingDaysPerWeek = 3, goalWeightKg = 80f))
        val text = """
            {"schema":"${ExternalPlanSchema.SCHEMA}","days":[{"dayOfWeek":1,"items":[
                {"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}]}],
             "profile":{"trainingDaysPerWeek":5,"goalWeightKg":80}}
        """.trimIndent()

        val ready = useCase()(text, targetWeek) as ExternalPlanImport.Ready

        assertEquals(
            "只留下真的会变的：80kg 和现在一样 → 不占一行；3→5 天要改",
            listOf(ProfileFieldDiff.DaysChange(from = 3, to = 5)),
            ready.profileDiffs,
        )
    }

    @Test
    fun import_ready_carriesPerItemReasonsKeyedByDayAndExercise() = runTest {
        stub(library)
        val text = document(
            """{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12,"reason":"做满且强度有余量"}""",
            day = 1,
        )

        val ready = useCase()(text, targetWeek) as ExternalPlanImport.Ready

        assertEquals(
            "投影成 WeekPlan 时理由会被丢掉（库里没这一列），所以要在丢之前按「天 × 动作」摘出来",
            mapOf((1 to 1L) to "做满且强度有余量"),
            ready.reasons,
        )
    }

    // ---------------- 保护规则确实生效（不是只在投影器里说说） ----------------

    @Test
    fun import_userEditedSlotIsSkippedAndCountedAsPreserved() = runTest {
        stub(
            library = library,
            weekRows = listOf(row(id = 11L, day = 1, exerciseId = 1L, isUserEdited = true)),
        )

        val ready = useCase()(document(twoItems), targetWeek) as ExternalPlanImport.Ready

        assertEquals(
            "周一 × 深蹲被手改行挡住，只写进卧推",
            listOf(2L),
            ready.preview.draftsByDay.getValue(1).map { it.exerciseId },
        )
        assertEquals(
            "「已保留 N 条」要用**投影时**看到的真实行 id，否则报的是用户找不到的数",
            1,
            ready.preview.preservedCount,
        )
    }

    @Test
    fun import_dayOwnedByTemplateIsLeftAloneAndReported() = runTest {
        stub(
            library = library,
            weekRows = emptyList(),
            templateRows = listOf(row(id = 21L, day = 1, exerciseId = 1L, isUserEdited = true, weekStart = 0L)),
        )

        val result = useCase()(document("""{"exercise":"卧推","targetSets":3,"targetReps":10}""", day = 1), targetWeek)

        val nothing = result as ExternalPlanImport.NothingAdoptable
        assertTrue("整天交回模板 → 没有任何草案", nothing.preview.allDrafts.isEmpty())
        assertEquals(
            "原因要如实交回界面（这天由「每周相同」模板负责）",
            setOf(1),
            nothing.preview.templateOwnedDays,
        )
    }

    // ---------------- 三态分派 ----------------

    @Test
    fun import_wrongSchema_isRefusedWithoutReadingTheWeek() = runTest {
        stub(library)

        val refused = useCase()("""{"days":[]}""", targetWeek) as ExternalPlanImport.Refused

        assertEquals(ExternalDocRefusal.WRONG_SCHEMA, refused.reason)
        coVerify(exactly = 0) { planRepository.getRowsForWeek(any()) }
    }

    @Test
    fun import_everyItemUnknown_isRefusedWithTheDropList() = runTest {
        stub(library)

        val refused = useCase()(
            document("""{"exercise":"库里没有的动作","targetSets":3,"targetReps":12}"""),
            targetWeek,
        ) as ExternalPlanImport.Refused

        assertEquals(ExternalDocRefusal.NO_USABLE_ITEMS, refused.reason)
        assertEquals(
            listOf(ExternalPlanNote.Kind.EXERCISE_CREATABLE),
            refused.notes.map { it.kind },
        )
    }

    @Test
    fun import_allSlotsBlocked_isNothingAdoptable_notAQuietEmptyPreview() = runTest {
        stub(
            library = library,
            weekRows = listOf(
                row(id = 11L, day = 1, exerciseId = 1L, isUserEdited = true),
                row(id = 12L, day = 1, exerciseId = 2L, isUserEdited = true),
            ),
        )

        val result = useCase()(document(twoItems), targetWeek)

        val nothing = result as ExternalPlanImport.NothingAdoptable
        assertTrue("文档合法但没地方可写 → 草案为空", nothing.preview.allDrafts.isEmpty())
        assertEquals(
            "原因必须跟着回来：两条都是用户手改行，「已保留 2 条」就是界面要说的话",
            2,
            nothing.preview.preservedCount,
        )
    }

    // ---------------- 饮食段（刀 3）----------------

    private fun food(id: Long, name: String, kcalPer100g: Int, proteinPer100g: Double) = Food(
        id = id,
        name = name,
        kcalPer100g = kcalPer100g,
        proteinPer100g = proteinPer100g,
        carbsPer100g = 0.0,
        fatPer100g = 0.0,
    )

    private val dietText: String = """
        {"schema":"${ExternalPlanSchema.DIET_SCHEMA}","meals":[{"dayOfWeek":2,"entries":[
            {"mealType":"LUNCH","items":[{"food":"米饭（蒸）","grams":200}]}]}]}
    """.trimIndent()

    @Test
    fun import_dietOnlyDocumentIsReady_notNothingAdoptable() = runTest {
        // 只排吃、没排练的文档以前会落到 NothingAdoptable，屏幕上是那句
        // 「槽位都被你自己的改动挡住了」—— 对一份训练条目为零的文档，那句话是错的。
        stub(library, foods = listOf(food(11L, "米饭（蒸）", 116, 2.6)))

        val ready = useCase()(dietText, targetWeek, ImportSection.DIET) as ExternalPlanImport.Ready

        assertTrue("训练侧确实没有草案", ready.preview.allDrafts.isEmpty())
        assertEquals(1, ready.meals.size)
        assertEquals(2, ready.meals.single().dayOfWeek)
        assertEquals("数字仍然只在解析层算好，这里不做第二遍", 232, ready.meals.single().kcal)
    }

    @Test
    fun import_readyCarriesTheWeeksMealSlots_forThePreservedAndHistoryLines() = runTest {
        stub(library, foods = listOf(food(11L, "米饭（蒸）", 116, 2.6)))
        // 只给那一天放行（`stub()` 里已把其它天返回空表）：`any()` 会让七天都读到同一行，
        // 那测的就不是"某一天的状态"，而是"整个星期都改过"。
        coEvery { mealRepository.getMealsIncludingInactive(targetWeek + 1L) } returns listOf(
            Meal(
                dateEpochDay = targetWeek + 1L,
                mealType = MealType.LUNCH,
                isUserEdited = true,
                isCompleted = true,
            ),
        )

        val ready = useCase()(dietText, targetWeek, ImportSection.DIET) as ExternalPlanImport.Ready

        val slot = ready.mealSlots.single()
        assertEquals(2, slot.dayOfWeek)
        assertEquals(MealType.LUNCH, slot.mealType)
        assertTrue("这一格用户改过 → 采纳时必须跳过，界面也要说", slot.isUserEdited)
        assertTrue("这一格勾过「吃了」 → 改它会同日改动本周平均摄入", slot.isCompleted)
    }

    @Test
    fun import_skipsTheWeekMealRead_whenTheDocumentHasNoMeals() = runTest {
        // 纯训练文档不该白跑 7 天查询（`getMealsIncludingInactive` 一天一次）。
        stub(library)

        useCase()(document(twoItems), targetWeek)

        coVerify(exactly = 0) { mealRepository.getMealsIncludingInactive(any()) }
    }
}
