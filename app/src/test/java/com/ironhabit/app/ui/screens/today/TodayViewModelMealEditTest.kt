package com.ironhabit.app.ui.screens.today

import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.model.TodayMeals
import com.ironhabit.app.domain.model.TodayOverview
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.usecase.DeleteMealUseCase
import com.ironhabit.app.domain.usecase.DetailedCheckInUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
import com.ironhabit.app.domain.usecase.GetTodayMealsUseCase
import com.ironhabit.app.domain.usecase.GetTodayOverviewUseCase
import com.ironhabit.app.domain.usecase.QuickCheckInUseCase
import com.ironhabit.app.domain.usecase.SetRpeUseCase
import com.ironhabit.app.domain.usecase.ToggleHabitUseCase
import com.ironhabit.app.domain.usecase.ToggleMealUseCase
import com.ironhabit.app.domain.usecase.ToggleSetUseCase
import com.ironhabit.app.domain.usecase.UndoCheckInUseCase
import com.ironhabit.app.domain.usecase.UpsertMealUseCase
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.test.MainDispatcherRule
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

    private fun newViewModel(): TodayViewModel {
        every { getTodayOverview.invoke(today) } returns flowOf(TodayOverview(dateEpochDay = today))
        every { getTodayOverview.invoke(pastDay) } returns flowOf(TodayOverview(dateEpochDay = pastDay))
        every { getTodayMeals.invoke(today) } returns flowOf(TodayMeals(meals = listOf(meal)))
        every { getTodayMeals.invoke(pastDay) } returns flowOf(TodayMeals())
        every { planRepository.observePlannedWeekdays() } returns flowOf(emptyList())
        // P3：今日页会读「每周相同」那份是否存在（开关状态）。
        every { planRepository.observeRepeatPlan() } returns flowOf(emptyList())
        every { checkInRepository.observeActiveDaysSince(any()) } returns flowOf(emptyList())

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
            upsertMeal = upsertMeal,
            checkInRepository = checkInRepository,
            planRepository = planRepository,
            clock = clock,
            timeZone = timeZone,
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
