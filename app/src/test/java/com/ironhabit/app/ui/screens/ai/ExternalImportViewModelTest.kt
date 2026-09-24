package com.ironhabit.app.ui.screens.ai

import com.ironhabit.app.R
import com.ironhabit.app.domain.ai.external.ExternalDocRefusal
import com.ironhabit.app.domain.ai.external.ExternalPlanNote
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.BodyReview
import com.ironhabit.app.domain.model.DietReview
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.usecase.BuildExternalCoachPromptUseCase
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
    private val holder = PlanPreviewHolder()
    private val utc = TimeZone.UTC

    /** 2026-09-24（周四）→ 本周一 = 9/21。 */
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-24T02:00:00Z")
    }

    private val thisMonday: Long = LocalDate(2026, 9, 21).toEpochDays().toLong()

    private fun viewModel(): ExternalImportViewModel = ExternalImportViewModel(
        buildPromptTemplate = buildPromptTemplate,
        importPlan = importPlan,
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
        val notes = listOf(ExternalPlanNote(ExternalPlanNote.Kind.UNKNOWN_EXERCISE, 1, "跳跃深蹲"))
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Ready(draftPreview, notes)
        vm.onTextChange("""{"schema":"ironhabit-plan-import/v1"}""")

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
        val notes = listOf(ExternalPlanNote(ExternalPlanNote.Kind.UNKNOWN_EXERCISE, 1, "不存在"))
        coEvery { importPlan(any(), any()) } returns ExternalPlanImport.Refused(
            ExternalDocRefusal.EMPTY_PLAN,
            notes,
            analysis = "未收到 library 数据",
        )
        vm.onTextChange("一段读不通的东西")

        vm.parse()
        advanceUntilIdle()

        assertEquals(R.string.ai_import_refuse_empty_plan, vm.uiState.value.refusalRes)
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
            ExternalDocRefusal.EMPTY_PLAN to R.string.ai_import_refuse_empty_plan,
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

        vm.onClipboardRead("""{"schema":"ironhabit-plan-import/v1"}""")

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
            listOf(ExternalPlanNote(ExternalPlanNote.Kind.UNKNOWN_EXERCISE, 1, "x")),
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
