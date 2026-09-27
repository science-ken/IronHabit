package com.ironhabit.app.ui.screens.today

import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.model.TodayMeals
import com.ironhabit.app.domain.model.TodayOverview
import com.ironhabit.app.domain.model.BodyReview
import com.ironhabit.app.domain.model.DietReview
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.StatsRepository
import com.ironhabit.app.domain.usecase.DeleteMealUseCase
import com.ironhabit.app.domain.usecase.DetailedCheckInUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
import com.ironhabit.app.domain.usecase.GetMealSlotStatesUseCase
import com.ironhabit.app.domain.usecase.GetTodayMealsUseCase
import com.ironhabit.app.domain.usecase.GetTodayOverviewUseCase
import com.ironhabit.app.domain.usecase.RestoreMealUseCase
import com.ironhabit.app.domain.usecase.QuickCheckInUseCase
import com.ironhabit.app.domain.usecase.SetRpeUseCase
import com.ironhabit.app.domain.usecase.ToggleHabitUseCase
import com.ironhabit.app.domain.usecase.ToggleMealUseCase
import com.ironhabit.app.domain.usecase.ToggleSetUseCase
import com.ironhabit.app.domain.usecase.UndoCheckInUseCase
import com.ironhabit.app.domain.usecase.UpsertMealUseCase
import com.ironhabit.app.domain.usecase.MealSlotState
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.test.MainDispatcherRule
import com.ironhabit.app.test.todayClockFor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 「编辑一餐内容」（产品基线的编辑入口）行为验证：
 * 弹层开关、写入口径（**所选日**）、清洗与转发、失败不丢输入、未打开时不写库。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelMealEditTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getTodayOverview = mockk<GetTodayOverviewUseCase>()
    /** 「编辑这一餐」弹层的槽位占用来源：默认给空图（= 四颗都可点），需要时各测试自己改桩。 */
    private val getMealSlotStates = mockk<GetMealSlotStatesUseCase>(relaxed = true)
    private val restoreMeal = mockk<RestoreMealUseCase>()
    private val quickCheckIn = mockk<QuickCheckInUseCase>(relaxed = true)
    private val detailedCheckIn = mockk<DetailedCheckInUseCase>(relaxed = true)
    private val undoCheckIn = mockk<UndoCheckInUseCase>(relaxed = true)
    private val toggleHabit = mockk<ToggleHabitUseCase>(relaxed = true)
    private val toggleSet = mockk<ToggleSetUseCase>(relaxed = true)
    private val setRpe = mockk<SetRpeUseCase>(relaxed = true)
    private val getTodayMeals = mockk<GetTodayMealsUseCase>()
    private val toggleMeal = mockk<ToggleMealUseCase>(relaxed = true)
    private val generateDietPlan = mockk<GenerateDietPlanUseCase>(relaxed = true)
    private val deleteMeal = mockk<DeleteMealUseCase>(relaxed = true)
    private val upsertMeal = mockk<UpsertMealUseCase>()
    private val checkInRepository = mockk<CheckInRepository>(relaxed = true)
    private val planRepository = mockk<PlanRepository>(relaxed = true)
    private val buildWeeklyReview = mockk<BuildWeeklyReviewUseCase>()
    private val statsRepository = mockk<StatsRepository>(relaxed = true)

    /** 与周磁贴无关的用例：一份「本周什么都没练」的复盘 → 两块周磁贴都不渲染。 */
    private fun emptyWeekReview(): WeeklyReview = WeeklyReview(
        weekStartEpochDay = 0L,
        weekEndEpochDay = 6L,
        training = TrainingReview(
            plannedDays = 0,
            completedDays = 0,
            totalVolumeKg = 0f,
            totalSets = 0,
            avgRpe = null,
            progressed = emptyList(),
            stalled = emptyList(),
        ),
        body = BodyReview(startWeightKg = null, latestWeightKg = null),
        diet = DietReview(loggedDays = 0, avgKcal = null, avgProteinG = null),
    )

    private val clock = Clock.System
    private val timeZone = TimeZone.UTC
    private val today: Long = DateUtils.todayEpochDay(Clock.System, TimeZone.UTC)
    private val pastDay: Long = today - 2

    /** 被编辑的那一餐（规则生成的晚餐）。 */
    private val meal = Meal(
        id = 77L,
        dateEpochDay = today,
        mealType = MealType.DINNER,
        items = listOf("瘦牛肉 120g", "米饭 150g"),
        kcal = 420,
        proteinG = 35.0,
    )

    /** @param todayMeals 需要验证别的餐组合（比如带软删行）时由调用方给，默认只有那一条生效餐。 */
    private fun newViewModel(
        todayMeals: TodayMeals = TodayMeals(meals = listOf(meal)),
    ): TodayViewModel {
        every { getTodayOverview.invoke(today) } returns flowOf(TodayOverview(dateEpochDay = today))
        every { getTodayOverview.invoke(pastDay) } returns flowOf(TodayOverview(dateEpochDay = pastDay))
        every { getTodayMeals.invoke(today) } returns flowOf(todayMeals)
        every { getTodayMeals.invoke(pastDay) } returns flowOf(TodayMeals())
        every { planRepository.observePlannedWeekdays() } returns flowOf(emptyList())
        // P3：今日页会读「每周相同」那份是否存在（开关状态）。
        every { planRepository.observeRepeatPlan() } returns flowOf(emptyList())
        every { checkInRepository.observeActiveDaysSince(any()) } returns flowOf(emptyList())
        // 本用例不验证周磁贴：给一份「本周什么都没练」的复盘 → 磁贴整块不渲染。
        coEvery { buildWeeklyReview.invoke(any()) } returns emptyWeekReview()

        return TodayViewModel(
            getTodayOverview = getTodayOverview,
            quickCheckIn = quickCheckIn,
            detailedCheckIn = detailedCheckIn,
            undoCheckIn = undoCheckIn,
            toggleHabit = toggleHabit,
            toggleSet = toggleSet,
            setRpe = setRpe,
            getTodayMeals = getTodayMeals,
            toggleMeal = toggleMeal,
            generateDietPlan = generateDietPlan,
            generateTrainingPlan = mockk<GenerateTrainingPlanUseCase>(relaxed = true),
            deleteMeal = deleteMeal,
            restoreMeal = restoreMeal,
            upsertMeal = upsertMeal,
            // 本用例只验证「编辑这一餐」弹层：条目相关的协作者给静默假实现即可。
            addMealItem = mockk<com.ironhabit.app.domain.usecase.AddMealItemUseCase>(relaxed = true),
            changePortion = mockk<com.ironhabit.app.domain.usecase.ChangeMealItemPortionUseCase>(relaxed = true),
            // 「编辑这一餐」弹层要读槽位占用；本组用例不验证它，给空图即可。
            getMealSlotStates = getMealSlotStates,
            mealItemRepository = mockk<com.ironhabit.app.domain.repository.MealItemRepository>(relaxed = true),
            checkInRepository = checkInRepository,
            planRepository = planRepository,
            buildWeeklyReview = buildWeeklyReview,
            statsRepository = statsRepository,
            planPreviewHolder = com.ironhabit.app.domain.usecase.PlanPreviewHolder(),
            todayClock = todayClockFor(clock, timeZone),
        )
    }

    @Test
    fun openAndDismissEditor_togglesEditingMeal() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertNull("初始不应有正在编辑的餐", viewModel.uiState.value.editingMeal)

        viewModel.onOpenMealEditor(meal)
        assertEquals(meal, viewModel.uiState.value.editingMeal)

        viewModel.onDismissMealEditor()
        assertNull("取消后应关闭弹层", viewModel.uiState.value.editingMeal)
    }

    /**
     * 打开弹层要顺手读一次槽位占用（审查报告 P2-1）。
     *
     * 判据本身在 `MealSlotStatesTest`，这一条钉的是**接线**：读的是**所选日**、
     * 排他 id 是**正在编辑的那一餐**，结果落到 `editingMealSlots` 供弹层灰掉 chip；
     * 关闭时要一起清掉（否则下次打开会先闪一下上一天的占用）。
     */
    @Test
    fun openingTheEditorLoadsSlotOccupancyForTheSelectedDay() =
        runTest(mainDispatcherRule.testDispatcher) {
            val slots = mapOf(
                MealType.LUNCH to MealSlotState.TAKEN,
                MealType.SNACK to MealSlotState.DELETED,
            )
            coEvery { getMealSlotStates(today, excludeMealId = 77L) } returns slots
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.onOpenMealEditor(meal)
            advanceUntilIdle()

            assertEquals(
                "弹层拿不到占用就没法灰掉「点了必然报错」的那颗 chip",
                slots, viewModel.uiState.value.editingMealSlots,
            )

            viewModel.onDismissMealEditor()
            advanceUntilIdle()
            assertTrue(
                "关闭弹层要把占用一起清掉",
                viewModel.uiState.value.editingMealSlots.isEmpty(),
            )
        }

    /**
     * 恢复一餐：只把 id 交给用例，并把「已恢复」说回去。
     *
     * 这一格存在的理由是软删的语义 —— 行一直在库里，缺的从来不是数据，是入口。
     */
    @Test
    fun restoreMeal_forwardsIdAndSaysRestored() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { restoreMeal(77L) } returns Unit
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.onRestoreMeal(meal)
        advanceUntilIdle()

        coVerify(exactly = 1) { restoreMeal(77L) }
        assertEquals(R.string.msg_restored, viewModel.uiState.value.snackbarRes)
    }

    /** 「已删除 N 条」的展开门控：收起时界面拿不到那几行，展开才交出去。 */
    @Test
    fun deletedMealsGateBehindTheToggle() = runTest(mainDispatcherRule.testDispatcher) {
        val deleted = meal.copy(id = 88L, mealType = MealType.SNACK, isActive = false)
        val viewModel = newViewModel(
            TodayMeals(meals = listOf(meal), deletedMeals = listOf(deleted)),
        )
        advanceUntilIdle()

        assertEquals("软删的餐要带出来，否则删完就找不回来了", listOf(deleted), viewModel.uiState.value.deletedMeals)
        assertTrue("默认收起", viewModel.uiState.value.visibleDeletedMeals.isEmpty())

        viewModel.onToggleDeletedMeals()
        assertEquals("展开后交出那几行", listOf(deleted), viewModel.uiState.value.visibleDeletedMeals)

        viewModel.onToggleDeletedMeals()
        assertTrue("再点收回", viewModel.uiState.value.visibleDeletedMeals.isEmpty())
    }

    @Test
    fun saveEdit_forwardsContent_andClosesEditor() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { upsertMeal(any(), any(), any(), any(), any(), any()) } returns 77L
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.onOpenMealEditor(meal)
        viewModel.onSaveMealEdit(
            mealType = MealType.SNACK,
            items = listOf("酸奶 150g", "香蕉 1 根"),
            kcal = 210,
            proteinG = 9.5,
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            upsertMeal(
                id = 77L,
                epochDay = today,
                mealType = MealType.SNACK,
                items = listOf("酸奶 150g", "香蕉 1 根"),
                kcal = 210,
                proteinG = 9.5,
            )
        }
        assertNull("保存成功应关闭弹层", viewModel.uiState.value.editingMeal)
        assertEquals(
            "保存成功应给提示",
            R.string.msg_meal_saved,
            viewModel.uiState.value.snackbarRes,
        )
    }

    @Test
    fun saveEdit_writesToSelectedDayNotToday() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { upsertMeal(any(), any(), any(), any(), any(), any()) } returns 77L
        val viewModel = newViewModel()
        advanceUntilIdle()

        // 切到前几天再编辑：写入口径必须跟「所选日」走（与逐组/习惯/补录一致）。
        viewModel.onSelectEpochDay(pastDay)
        advanceUntilIdle()
        viewModel.onOpenMealEditor(meal)
        viewModel.onSaveMealEdit(
            mealType = MealType.DINNER,
            items = listOf("鸡胸肉 150g"),
            kcal = 250,
            proteinG = 40.0,
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            upsertMeal(
                id = 77L,
                epochDay = pastDay,
                mealType = MealType.DINNER,
                items = listOf("鸡胸肉 150g"),
                kcal = 250,
                proteinG = 40.0,
            )
        }
    }

    @Test
    fun saveEdit_withoutOpenMeal_doesNotWrite() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        // 没有正在编辑的餐（例如 UI 状态错乱）→ 不应写库，也不能崩。
        viewModel.onSaveMealEdit(MealType.LUNCH, listOf("米饭"), 200, 4.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { upsertMeal(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun saveEdit_failure_keepsEditorOpen_andShowsError() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { upsertMeal(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("db closed")
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.onOpenMealEditor(meal)
        viewModel.onSaveMealEdit(MealType.DINNER, listOf("鸡胸肉 150g"), 250, 40.0)
        advanceUntilIdle()

        assertEquals(
            "失败应保持弹层打开（输入不丢，可直接重试）",
            meal,
            viewModel.uiState.value.editingMeal,
        )
        assertEquals(R.string.error_generic, viewModel.uiState.value.errorRes)
    }
}
