package com.ironhabit.app.ui.screens.ai

import com.ironhabit.app.R
import com.ironhabit.app.domain.ai.external.ExternalDocRefusal
import com.ironhabit.app.domain.ai.external.ExternalPlanNote
import com.ironhabit.app.domain.ai.external.ExternalPlanSchema
import com.ironhabit.app.domain.ai.external.ImportedNewExercise
import com.ironhabit.app.domain.ai.external.ImportedNewFood
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.BodyReview
import com.ironhabit.app.domain.model.DietReview
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.usecase.BuildExternalCoachPromptUseCase
import com.ironhabit.app.domain.usecase.BuildExternalDietPromptUseCase
import com.ironhabit.app.domain.usecase.CreateImportedExercisesUseCase
import com.ironhabit.app.domain.usecase.CreateImportedFoodsUseCase
import com.ironhabit.app.domain.usecase.ExternalPlanImport
import com.ironhabit.app.domain.usecase.ImportExternalPlanUseCase
import com.ironhabit.app.domain.usecase.PlanPreview
import com.ironhabit.app.domain.usecase.PlanPreviewHolder
import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [ExternalImportViewModel] 的状态机单测。
 *
 * 钉的是三态分派有没有接错线，尤其是两条最容易接错的：
 * - 解析成功必须把**草案 + 清单一起**交给 holder（只交草案 = 预览页没法摊开"少导了什么"）；
 * - `NothingAdoptable` 绝不能跳预览页（跳过去只有一句「没有待采纳的草案」，等于把人支走又不说原因）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalImportViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val importPlan = mockk<ImportExternalPlanUseCase>()
    private val buildPromptTemplate = mockk<BuildExternalCoachPromptUseCase>(relaxed = true)
    private val buildDietPrompt = mockk<BuildExternalDietPromptUseCase>(relaxed = true)
    private val createExercises = mockk<CreateImportedExercisesUseCase>()
    private val createFoods = mockk<CreateImportedFoodsUseCase>()
    private val holder = PlanPreviewHolder()
    private val utc = TimeZone.UTC

    /** 2026-09-24（周四）→ 本周一 = 9/21。 */
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-24T02:00:00Z")
    }

    private val thisMonday: Long = LocalDate(2026, 9, 21).toEpochDays().toLong()

    private fun viewModel(): ExternalImportViewModel = ExternalImportViewModel(
        buildPromptTemplate = buildPromptTemplate,
        buildDietPrompt = buildDietPrompt,
        importPlan = importPlan,
        createExercises = createExercises,
        createFoods = createFoods,
        planPreviewHolder = holder,
        clock = clock,
        timeZone = utc,
    )

    private val draftPreview: PlanPreview = PlanPreview(
        weekStartEpochDay = thisMonday,
        draftsByDay = mapOf(
            1 to listOf(
                WeekPlan(
                    exerciseId = 7L,
                    dayOfWeek = 1,
                    targetSets = 4,
                    targetReps = 8,
                    weekStartEpochDay = thisMonday,
                ),
            ),
        ),
        weekRowsForRetirement = emptyList(),
        templateOwnedDays = emptySet(),
        source = AdviceSource.EXTERNAL_AI_IMPORT,
    )

    private val review: WeeklyReview = WeeklyReview(
        weekStartEpochDay = thisMonday,
        weekEndEpochDay = thisMonday + 6,
        training = TrainingReview(0, 0, 0f, 0, avgRpe = null, progressed = emptyList(), stalled = emptyList()),
        body = BodyReview(startWeightKg = null, latestWeightKg = null, sampleCount = 0),
        diet = DietReview(loggedDays = 0, avgKcal = null, avgProteinG = null),
    )

    // ---------------- 选周 ----------------

    @Test
    fun weekChoice_offersOnlyThisWeekAndNextWeek() {
        val vm = viewModel()

        assertEquals(thisMonday, vm.weekStartEpochDay(ExternalImportViewModel.WeekChoice.THIS_WEEK))
        assertEquals(thisMonday + 7L, vm.weekStartEpochDay(ExternalImportViewModel.WeekChoice.NEXT_WEEK))
    }

    @Test
    fun open_buildsTheTemplateRightAwaySoTheFirstTapActuallyCopies() = runTest {
        // 真机上踩过的：点第一次「复制提问模板」只是开始拼装、什么都没复制，
        // 用户读解成"按钮坏了"。打开弹层本身就是"我要模板"，拼装必须在它背后跑完。
        coEvery { buildPromptTemplate(any(), thisMonday) } returns "T-this"
        val vm = viewModel()

        vm.open(review)
        advanceUntilIdle()

        assertEquals("T-this", vm.uiState.value.template)
        assertFalse(vm.uiState.value.isBuildingTemplate)
    }

    @Test
    fun open_withoutReviewYet_sendsNothingButTheLaterReviewRebuildsIt() = runTest {
        coEvery { buildPromptTemplate(any(), thisMonday) } returns "T-this"
        val vm = viewModel()

        vm.open(null)
        advanceUntilIdle()
        coVerify(exactly = 0) { buildPromptTemplate(any(), any()) }
        assertNull(vm.uiState.value.template)

        vm.onReviewAvailable(review)
        advanceUntilIdle()

        assertEquals(
            "复盘比弹层打开更晚算完时，模板必须自己补上，否则按钮永远是死的",
            "T-this",
            vm.uiState.value.template,
        )
    }

    @Test
    fun onWeekChange_rebuildsForTheNewWeek_notJustClearsTheOldOne() = runTest {
        coEvery { buildPromptTemplate(any(), thisMonday) } returns "T-this"
        coEvery { buildPromptTemplate(any(), thisMonday + 7) } returns "T-next"
        val vm = viewModel()
        vm.open(review)
        advanceUntilIdle()
        assertEquals("T-this", vm.uiState.value.template)

        vm.onTextChange("已经粘好的东西")
        vm.onWeekChange(ExternalImportViewModel.WeekChoice.NEXT_WEEK)
        advanceUntilIdle()

        assertEquals(
            "模板里「这一周已经排了什么」是按周拼的：换周必须重拼，不能只清空等用户再点",
            "T-next",
            vm.uiState.value.template,
        )
        assertEquals("粘贴框不该跟着清空", "已经粘好的东西", vm.uiState.value.text)
    }

    // ---------------- 三态分派 ----------------

    @Test
    fun parse_ready_handsPreviewAndNotesToHolderAndAsksForNavigation() = runTest {
        val vm = viewModel()
        val notes = listOf(ExternalPlanNote(ExternalPlanNote.Kind.EXERCISE_INACTIVE, 1, "跳跃深蹲"))
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Ready(draftPreview, notes)
        vm.onTextChange("""{"schema":"${ExternalPlanSchema.SCHEMA}"}""")

        vm.parse()
        advanceUntilIdle()

        assertEquals(draftPreview, holder.peek())
        assertEquals(
            "清单必须和草案一起进 holder：预览页要在**采纳之前**把它摊开",
            notes,
            holder.peekImportNotes(),
        )
        assertTrue(vm.uiState.value.previewRequested)
        assertFalse(vm.uiState.value.sheetOpen)
        assertEquals("", vm.uiState.value.text)
        coVerify(exactly = 1) { importPlan(any(), thisMonday) }
    }

    @Test
    fun parse_refused_showsItsOwnSentenceAndWritesNothing() = runTest {
        val vm = viewModel()
        val notes = listOf(ExternalPlanNote(ExternalPlanNote.Kind.EXERCISE_INACTIVE, 1, "不存在"))
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(
            ExternalDocRefusal.NOTHING_TO_IMPORT,
            notes,
            analysis = "未收到 library 数据",
        )
        vm.onTextChange("一段读不通的东西")

        vm.parse()
        advanceUntilIdle()

        assertEquals(R.string.ai_import_refuse_nothing_to_import, vm.uiState.value.refusalRes)
        assertEquals(
            "模型自己那句话要交给界面贴出来，它比 App 猜的原因准",
            "未收到 library 数据",
            vm.uiState.value.refusalAnalysis,
        )
        assertEquals(notes, vm.uiState.value.notes)
        assertFalse("没跳页", vm.uiState.value.previewRequested)
        assertNull(holder.peek())
        assertEquals("粘的内容要留着，用户改两个字就能再试", "一段读不通的东西", vm.uiState.value.text)
    }

    @Test
    fun parse_everyRefusalReasonHasItsOwnSentence() = runTest {
        // 五种原因五句话。穷尽映射哪天退化成一句"格式错误"，用户就不知道下一步该干什么。
        val expected = mapOf(
            ExternalDocRefusal.EMPTY_DOCUMENT to R.string.ai_import_refuse_empty,
            ExternalDocRefusal.NOT_A_DOCUMENT to R.string.ai_import_refuse_not_json,
            ExternalDocRefusal.WRONG_SCHEMA to R.string.ai_import_refuse_wrong_schema,
            ExternalDocRefusal.TOO_LARGE to R.string.ai_import_refuse_too_large,
            ExternalDocRefusal.NOTHING_TO_IMPORT to R.string.ai_import_refuse_nothing_to_import,
            ExternalDocRefusal.NO_USABLE_ITEMS to R.string.ai_import_refuse_no_items,
        )
        val vm = viewModel()

        for ((reason, res) in expected) {
            coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(reason)
            vm.onTextChange("x")

            vm.parse()
            advanceUntilIdle()

            assertEquals(res, vm.uiState.value.refusalRes)
        }
    }

    @Test
    fun parse_nothingAdoptable_reportsTheCountAndNeverNavigates() = runTest {
        val vm = viewModel()
        val blocked = draftPreview.copy(draftsByDay = emptyMap(), preservedCount = 2)
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.NothingAdoptable(blocked, emptyList())
        vm.onTextChange("valid doc")

        vm.parse()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.previewRequested)
        assertNull("holder 一个字都没写 → 预览页即便被打开也是空的", holder.peek())
        assertEquals(R.string.ai_import_nothing_adoptable, vm.uiState.value.snackbarRes)
        assertEquals(
            "报的是用户找得着的手改条数，且必须是字符串实参（那条文案用字符串占位符，塞数字会运行时崩）",
            listOf("2"),
            vm.uiState.value.snackbarArgs,
        )
    }

    @Test
    fun parse_secondCallIsIgnoredWhileOneIsRunning() = runTest {
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(ExternalDocRefusal.EMPTY_DOCUMENT)
        vm.onTextChange("x")

        vm.parse()
        vm.parse()
        advanceUntilIdle()

        coVerify(exactly = 1) { importPlan(any(), any()) }
    }

    // ---------------- 新动作待确认（刀 4）----------------

    private fun candidate(name: String) = ImportedNewExercise(
        name = name,
        category = ExerciseCategory.STRENGTH,
        muscleGroups = listOf("腿部"),
        equipment = setOf(Equipment.DUMBBELL),
    )

    @Test
    fun parse_withCandidates_showsThemUnchecked() = runTest {
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(
            reason = ExternalDocRefusal.NO_USABLE_ITEMS,
            newExercises = listOf(candidate("动作甲"), candidate("动作乙")),
        )
        vm.onTextChange("doc")

        vm.parse()
        advanceUntilIdle()

        val rows = vm.uiState.value.newExercises
        assertEquals(2, rows.size)
        assertTrue(
            "默认一行都不勾：建动作是往用户库里永久加一行，文档「想要」不等于用户「同意」",
            rows.none { row -> row.checked },
        )
    }

    @Test
    fun createSelected_createsOnlyTheCheckedOnes_andReparsesRightAway() = runTest {
        val vm = viewModel()
        val first = candidate("动作甲")
        val second = candidate("动作乙")
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(
            reason = ExternalDocRefusal.NO_USABLE_ITEMS,
            newExercises = listOf(first, second),
        )
        coEvery { createExercises(any()) } returns 1
        vm.onTextChange("doc")
        vm.parse()
        advanceUntilIdle()

        vm.onToggleNewExercise(1)
        vm.createSelectedExercisesAndReparse()
        advanceUntilIdle()

        coVerify(exactly = 1) { createExercises(listOf(second)) }
        assertEquals(R.string.ai_import_exercises_created, vm.uiState.value.snackbarRes)
        assertEquals(listOf("1"), vm.uiState.value.snackbarArgs)
        coVerify(exactly = 2) { importPlan(any(), any()) }
    }

    @Test
    fun createSelected_withNothingChecked_createsNothingAndDoesNotReparse() = runTest {
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(
            reason = ExternalDocRefusal.NO_USABLE_ITEMS,
            newExercises = listOf(candidate("动作甲")),
        )
        vm.onTextChange("doc")
        vm.parse()
        advanceUntilIdle()

        vm.createSelectedExercisesAndReparse()
        advanceUntilIdle()

        coVerify(exactly = 0) { createExercises(any()) }
        coVerify(exactly = 1) { importPlan(any(), any()) }
    }

    @Test
    fun parse_readyWithNewExerciseCandidates_staysInSheetInsteadOfNavigating() = runTest {
        // 真机自测抓到的：以前这条路照样跳预览页，而预览页写着"勾上面「加入动作库」"——
        // 那块按钮在已经关掉的弹层里，界面指着一块不在屏幕上的 UI。
        val vm = viewModel()
        val pending = ExternalPlanImport.Ready(
            preview = draftPreview,
            notes = listOf(ExternalPlanNote(ExternalPlanNote.Kind.EXERCISE_CREATABLE, 1, "动作乙")),
            newExercises = listOf(candidate("动作乙")),
        )
        coEvery { importPlan(any(), any()) } returns pending
        vm.open(review)
        vm.onTextChange("doc")

        vm.parse()
        advanceUntilIdle()

        assertTrue("不跳页", vm.uiState.value.sheetOpen)
        assertFalse(vm.uiState.value.previewRequested)
        assertTrue(vm.uiState.value.canProceedWithoutThem)
        assertNull("草案还没交给预览页", holder.peek())
    }

    @Test
    fun proceedWithoutCandidates_handsTheDraftOverAndNavigates() = runTest {
        val vm = viewModel()
        val notes = listOf(ExternalPlanNote(ExternalPlanNote.Kind.EXERCISE_CREATABLE, 1, "动作乙"))
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Ready(
            preview = draftPreview,
            notes = notes,
            newExercises = listOf(candidate("动作乙")),
        )
        vm.onTextChange("doc")
        vm.parse()
        advanceUntilIdle()

        vm.proceedWithoutCandidates()
        advanceUntilIdle()

        assertEquals(draftPreview, holder.peek())
        assertEquals(notes, holder.peekImportNotes())
        assertTrue(vm.uiState.value.previewRequested)
        assertFalse(vm.uiState.value.sheetOpen)
        assertTrue(vm.uiState.value.newExercises.isEmpty())
        assertFalse(vm.uiState.value.canProceedWithoutThem)
    }

    @Test
    fun parse_readyWithoutCandidates_navigatesStraightAway() = runTest {
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Ready(draftPreview, emptyList())
        vm.onTextChange("doc")

        vm.parse()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.previewRequested)
        assertFalse(vm.uiState.value.sheetOpen)
    }

    // ---------------- 新食物待确认（刀 4：弹层内建库）----------------

    private fun newFood(
        name: String,
        kcal: Int? = 60,
        protein: Double? = 1.6,
        carbs: Double? = 13.0,
        fat: Double? = 0.1,
    ) = ImportedNewFood(
        name = name,
        kcalPer100g = kcal,
        proteinPer100g = protein,
        carbsPer100g = carbs,
        fatPer100g = fat,
    )

    @Test
    fun parse_withUnknownFoods_prefilledRowsAreCheckedAndOthersAreNot() = runTest {
        // 预填齐了 → 默认勾上（用户就是为"库里没有也要能加"才走到这一步的）；
        // 缺一格 → 不勾、也勾不动，因为那一格的空白是"还不知道"，不是 0。
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(
            reason = ExternalDocRefusal.NO_USABLE_ITEMS,
            newFoods = listOf(newFood("紫薯"), newFood("秋葵", kcal = null, protein = null, carbs = null, fat = null)),
        )
        vm.onTextChange("doc")

        vm.parse()
        advanceUntilIdle()

        val rows = vm.uiState.value.newFoods
        assertEquals(listOf("紫薯", "秋葵"), rows.map { row -> row.name })
        assertTrue("四项都有值 → 默认勾上", rows[0].checked && rows[0].canBuild)
        assertTrue("缺数值 → 不给勾", rows[1].canBuild.not())
        assertTrue(rows[1].checked.not())
        assertEquals("预填要看得见，用户才知道自己认的是哪个数", "60", rows[0].kcal)
        assertEquals("1.6", rows[0].protein)
    }

    @Test
    fun parse_readyWithNewFoodCandidates_staysInSheetInsteadOfNavigating() = runTest {
        // 动作侧踩过的同一个坑，食物侧必须一起挡：跳了预览页，那块「加入食物库」就在已经关掉的弹层里，
        // 而预览页那句「少算了 N 条」会指着屏幕外的一颗按钮。
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Ready(
            preview = draftPreview,
            notes = listOf(ExternalPlanNote(ExternalPlanNote.Kind.FOOD_CREATABLE, 1, "紫薯")),
            newFoods = listOf(newFood("紫薯")),
        )
        vm.open(review)
        vm.onTextChange("doc")

        vm.parse()
        advanceUntilIdle()

        assertTrue("不跳页", vm.uiState.value.sheetOpen)
        assertFalse(vm.uiState.value.previewRequested)
        assertTrue(vm.uiState.value.canProceedWithoutThem)
        assertEquals("紫薯", vm.uiState.value.newFoods.single().name)
        assertNull("草案还没交给预览页", holder.peek())
    }

    @Test
    fun fillingTheFourBoxes_thenChecking_buildsWithTheTypedValuesAndTags() = runTest {
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(
            reason = ExternalDocRefusal.NO_USABLE_ITEMS,
            newFoods = listOf(newFood("秋葵", kcal = null, protein = null, carbs = null, fat = null)),
        )
        coEvery { createFoods(any()) } returns 1
        vm.onTextChange("doc")
        vm.parse()
        advanceUntilIdle()

        vm.onNewFoodFieldChange(0, ExternalImportViewModel.NewFoodField.KCAL, "33")
        vm.onNewFoodFieldChange(0, ExternalImportViewModel.NewFoodField.PROTEIN, "1.9")
        vm.onNewFoodFieldChange(0, ExternalImportViewModel.NewFoodField.CARBS, "7.0")
        vm.onNewFoodFieldChange(0, ExternalImportViewModel.NewFoodField.FAT, "0.2")
        vm.onToggleNewFood(0)
        vm.onToggleNewFoodTag(0, DietRestriction.SPICY)

        assertTrue(vm.uiState.value.newFoods.single().canBuild)
        vm.createSelectedFoodsAndReparse()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            createFoods(
                listOf(
                    ImportedNewFood(
                        name = "秋葵",
                        kcalPer100g = 33,
                        proteinPer100g = 1.9,
                        carbsPer100g = 7.0,
                        fatPer100g = 0.2,
                        dietaryTags = setOf(DietRestriction.SPICY),
                    ),
                ),
            )
        }
        assertEquals(R.string.ai_import_foods_created, vm.uiState.value.snackbarRes)
        assertEquals(listOf("1"), vm.uiState.value.snackbarArgs)
        coVerify(exactly = 2) { importPlan(any(), any()) }
    }

    @Test
    fun createSelectedFoods_skipsUncheckedAndIncompleteRows_andDoesNotReparse() = runTest {
        // 一行都没勾 → 一次都不该建，也不该白跑一次解析（"什么都不建"是合法选择，不是半成品）。
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(
            reason = ExternalDocRefusal.NO_USABLE_ITEMS,
            newFoods = listOf(newFood("紫薯"), newFood("秋葵", kcal = null, protein = null, carbs = null, fat = null)),
        )
        vm.onTextChange("doc")
        vm.parse()
        advanceUntilIdle()

        vm.onToggleNewFood(0)   // 把预填好的那条勾掉 —— 用户有权不建
        vm.onToggleNewFood(1)   // 没填齐的那条即使被勾上也不建（置灰是界面的事，这里再挡一道）

        vm.createSelectedFoodsAndReparse()
        advanceUntilIdle()

        coVerify(exactly = 0) { createFoods(any()) }
        coVerify(exactly = 1) { importPlan(any(), any()) }
    }

    @Test
    fun editingARowDownToIncompleteNumbers_takesItsCheckBackOff() = runTest {
        // 用户把已经预填好的数字清掉：这一行必须从"要建"里退出，否则建出一条 0 kcal/100g 的食物，
        // 而那一餐的合计会跟着一起偏小。
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(
            reason = ExternalDocRefusal.NO_USABLE_ITEMS,
            newFoods = listOf(newFood("紫薯")),
        )
        coEvery { createFoods(any()) } returns 0
        vm.onTextChange("doc")
        vm.parse()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.newFoods.single().checked)

        vm.onNewFoodFieldChange(0, ExternalImportViewModel.NewFoodField.KCAL, "")

        assertTrue(vm.uiState.value.newFoods.single().canBuild.not())
        vm.createSelectedFoodsAndReparse()
        advanceUntilIdle()
        coVerify(exactly = 0) { createFoods(any()) }
    }

    @Test
    fun outOfRangeNumbers_areNotBuildable_evenThoughTheyParse() = runTest {
        // 校验沿用食物库表单同一口径：热量 0..900、宏量 0..100。
        // 900 是纯脂肪的物理上限，超了就是漏打小数点 —— 建进库会长期算错每一餐。
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(
            reason = ExternalDocRefusal.NO_USABLE_ITEMS,
            newFoods = listOf(newFood("紫薯")),
        )
        vm.onTextChange("doc")
        vm.parse()
        advanceUntilIdle()

        vm.onNewFoodFieldChange(0, ExternalImportViewModel.NewFoodField.KCAL, "901")
        assertTrue(vm.uiState.value.newFoods.single().canBuild.not())

        vm.onNewFoodFieldChange(0, ExternalImportViewModel.NewFoodField.KCAL, "abc")
        assertTrue(vm.uiState.value.newFoods.single().canBuild.not())

        vm.onNewFoodFieldChange(0, ExternalImportViewModel.NewFoodField.KCAL, "60")
        assertTrue(vm.uiState.value.newFoods.single().canBuild)
    }

    @Test
    fun dismiss_andChangingTheText_dropTheWholeBuildForm() = runTest {
        // 建库表单里的半成品数值不能留到下一次解析：换了文档就是另一批食物。
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(
            reason = ExternalDocRefusal.NO_USABLE_ITEMS,
            newFoods = listOf(newFood("紫薯")),
        )
        vm.open(review)
        vm.onTextChange("doc")
        vm.parse()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.newFoods.size)

        vm.onTextChange("换一份文档")
        assertTrue(vm.uiState.value.newFoods.isEmpty())

        vm.parse()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.newFoods.size)
        vm.dismiss()
        assertTrue(vm.uiState.value.newFoods.isEmpty())
    }

    // ---------------- 剪贴板与关闭 ----------------

    @Test
    fun clipboardRead_blankClipboardOnlySaysSo() {
        val vm = viewModel()

        vm.onClipboardRead("   ")

        assertEquals(R.string.ai_import_clipboard_empty, vm.uiState.value.snackbarRes)
        assertEquals("", vm.uiState.value.text)
    }

    @Test
    fun clipboardRead_fillsThePasteBoxAndClearsThePreviousVerdict() = runTest {
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(ExternalDocRefusal.WRONG_SCHEMA)
        vm.onTextChange("旧的")
        vm.parse()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.refusalRes)

        vm.onClipboardRead("""{"schema":"${ExternalPlanSchema.SCHEMA}"}""")

        assertTrue(vm.uiState.value.text.startsWith("""{"schema"""))
        assertNull("换了内容就把上一次的判决清掉，否则新文本旁边挂着旧错误", vm.uiState.value.refusalRes)
    }

    @Test
    fun dismiss_clearsVerdictsButKeepsThePasteForTheNextTry() = runTest {
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(ExternalDocRefusal.WRONG_SCHEMA)
        vm.open(review)
        vm.onTextChange("doc")
        vm.parse()
        advanceUntilIdle()

        vm.dismiss()

        assertFalse(vm.uiState.value.sheetOpen)
        assertNull(vm.uiState.value.refusalRes)
        assertTrue(vm.uiState.value.notes.isEmpty())
        assertNull(vm.uiState.value.template)
    }

    @Test
    fun onTextChange_clearsThePreviousVerdict() = runTest {
        val vm = viewModel()
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(
            ExternalDocRefusal.NO_USABLE_ITEMS,
            listOf(ExternalPlanNote(ExternalPlanNote.Kind.EXERCISE_INACTIVE, 1, "x")),
        )
        vm.onTextChange("旧的")
        vm.parse()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notes.isNotEmpty())

        vm.onTextChange("改过的")

        assertTrue(vm.uiState.value.notes.isEmpty())
        assertNull(vm.uiState.value.refusalRes)
    }
}
