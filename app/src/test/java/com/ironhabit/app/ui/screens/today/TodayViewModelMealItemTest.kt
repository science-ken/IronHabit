package com.ironhabit.app.ui.screens.today

import com.ironhabit.app.R
import com.ironhabit.app.domain.model.BodyReview
import com.ironhabit.app.domain.model.DietReview
import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodNutrition
import com.ironhabit.app.domain.model.FoodServing
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealIntake
import com.ironhabit.app.domain.model.MealItem
import com.ironhabit.app.domain.model.MealTotals
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.model.TodayMeals
import com.ironhabit.app.domain.model.TodayOverview
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.MealItemRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.StatsRepository
import com.ironhabit.app.domain.usecase.AddMealItemResult
import com.ironhabit.app.domain.usecase.AddMealItemUseCase
import com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase
import com.ironhabit.app.domain.usecase.ChangeMealItemPortionUseCase
import com.ironhabit.app.domain.usecase.DeleteMealUseCase
import com.ironhabit.app.domain.usecase.DetailedCheckInUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
import com.ironhabit.app.domain.usecase.GetTodayMealsUseCase
import com.ironhabit.app.domain.usecase.GetTodayOverviewUseCase
import com.ironhabit.app.domain.usecase.PlanPreviewHolder
import com.ironhabit.app.domain.usecase.QuickCheckInUseCase
import com.ironhabit.app.domain.usecase.SetRpeUseCase
import com.ironhabit.app.domain.usecase.ToggleHabitUseCase
import com.ironhabit.app.domain.usecase.ToggleMealUseCase
import com.ironhabit.app.domain.usecase.ToggleSetUseCase
import com.ironhabit.app.domain.usecase.UndoCheckInUseCase
import com.ironhabit.app.domain.usecase.UpsertMealUseCase
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
 * 「一餐里实际吃了什么」的页面接线（第 2 刀 B）。
 *
 * 承重的那条是第一条：`applyData` 逐字段手写复制，漏搬 `mealItems` / `mealIntake`
 * 会让"记了条目但磁贴数字不动"成为一个编译、单测全绿的 bug —— 这个项目已经栽过四次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelMealItemTest {

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
    private val upsertMeal = mockk<UpsertMealUseCase>(relaxed = true)
    private val addMealItem = mockk<AddMealItemUseCase>()
    private val changePortion = mockk<ChangeMealItemPortionUseCase>(relaxed = true)
    private val mealItemRepository = mockk<MealItemRepository>(relaxed = true)
    private val checkInRepository = mockk<CheckInRepository>(relaxed = true)
    private val planRepository = mockk<PlanRepository>(relaxed = true)
    private val buildWeeklyReview = mockk<BuildWeeklyReviewUseCase>()
    private val statsRepository = mockk<StatsRepository>(relaxed = true)

    private val today: Long = DateUtils.todayEpochDay(Clock.System, TimeZone.UTC)

    private val meal = Meal(
        id = 77L,
        dateEpochDay = today,
        mealType = MealType.LUNCH,
        kcal = 420,
        proteinG = 35.0,
        isCompleted = false,
    )

    /** 一碗米饭：200 g 熟饭 ≈ 232 kcal。 */
    private val rice = Food(
        id = 3L,
        name = "米饭",
        kcalPer100g = 116,
        proteinPer100g = 2.6,
        carbsPer100g = 25.9,
        fatPer100g = 0.3,
        servings = listOf(FoodServing(id = 1L, unit = "碗", grams = 200)),
    )

    private val riceItem = MealItem(
        id = 500L,
        mealId = meal.id,
        foodId = rice.id,
        foodName = rice.name,
        grams = 200.0,
        servingUnit = "碗",
        servingCount = 1.0,
        nutrition = FoodNutrition(kcal = 232, proteinG = 5.2, carbsG = 51.8, fatG = 0.6),
    )

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

    private fun newViewModel(items: List<MealItem>, intake: MealIntake): TodayViewModel {
        every { getTodayOverview.invoke(today) } returns flowOf(TodayOverview(dateEpochDay = today))
        every { getTodayMeals.invoke(today) } returns flowOf(
            TodayMeals(
                meals = listOf(meal),
                totals = MealTotals(planKcal = 420, planProtein = 35.0),
                items = items,
                intake = intake,
                target = DietTarget(targetKcal = 2200, targetProtein = 130),
            ),
        )
        every { planRepository.observePlannedWeekdays() } returns flowOf(emptyList())
        every { planRepository.observeRepeatPlan() } returns flowOf(emptyList())
        every { checkInRepository.observeActiveDaysSince(any()) } returns flowOf(emptyList())
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
            planPreviewHolder = PlanPreviewHolder(),
            deleteMeal = deleteMeal,
            upsertMeal = upsertMeal,
            addMealItem = addMealItem,
            changePortion = changePortion,
            mealItemRepository = mealItemRepository,
            checkInRepository = checkInRepository,
            planRepository = planRepository,
            buildWeeklyReview = buildWeeklyReview,
            statsRepository = statsRepository,
            todayClock = todayClockFor(Clock.System, TimeZone.UTC),
        )
    }

    @Test
    fun mealItemsAndIntakeReachUiState() = runTest(mainDispatcherRule.testDispatcher) {
        val intake = MealIntake(
            kcal = 232,
            proteinG = 5.2,
            carbsG = 51.8,
            fatG = 0.6,
            preciseMeals = 1,
            coarseMealIds = emptySet(),
        )
        val vm = newViewModel(items = listOf(riceItem), intake = intake)
        advanceUntilIdle()

        assertEquals(listOf(riceItem), vm.uiState.value.mealItems)
        assertEquals("磁贴分子要走明细口径，不是 totals.intakeKcal", 232, vm.uiState.value.mealIntake.kcal)
        assertEquals(1, vm.uiState.value.mealIntake.preciseMeals)
    }

    @Test
    fun pickingMealForwardsServing_andKeepsPickerOpen() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { addMealItem.invoke(any(), any(), any(), any(), any()) } returns
            AddMealItemResult.Added(riceItem)
        val vm = newViewModel(items = emptyList(), intake = EMPTY_INTAKE)
        advanceUntilIdle()

        vm.onOpenFoodPicker(meal)
        vm.onPickFood(rice, rice.servings.first())
        advanceUntilIdle()

        coVerify(exactly = 1) {
            addMealItem.invoke(
                mealId = 77L,
                foodId = 3L,
                serving = rice.servings.first(),
                servingCount = 1.0,
                grams = null,
            )
        }
        assertEquals(R.string.msg_meal_item_added, vm.uiState.value.snackbarRes)
        assertTrue(
            "一顿饭通常要记好几样，点一样不能关一次弹层",
            vm.uiState.value.pickingMealId == 77L,
        )
    }

    /** 只能按克记的食物：先按 100 g 起记，克数在条目行上再改。 */
    @Test
    fun pickingFoodWithoutServing_logsDefault100Grams() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { addMealItem.invoke(any(), any(), any(), any(), any()) } returns
            AddMealItemResult.Added(riceItem)
        val vm = newViewModel(items = emptyList(), intake = EMPTY_INTAKE)
        advanceUntilIdle()
        vm.onOpenFoodPicker(meal)

        vm.onPickFood(rice.copy(servings = emptyList()), null)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            addMealItem.invoke(mealId = 77L, foodId = 3L, serving = null, servingCount = 1.0, grams = 100.0)
        }
    }

    @Test
    fun pickingFood_withoutPickerOpen_writesNothing() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel(items = emptyList(), intake = EMPTY_INTAKE)
        advanceUntilIdle()

        vm.onPickFood(rice, rice.servings.first())
        advanceUntilIdle()

        coVerify(exactly = 0) { addMealItem.invoke(any(), any(), any(), any(), any()) }
    }

    /** 到上限要说"最多 N 样"，不能糊成一句"失败了"。 */
    @Test
    fun mealFull_reportsLimitWithCount() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { addMealItem.invoke(any(), any(), any(), any(), any()) } returns AddMealItemResult.MealFull
        val vm = newViewModel(items = emptyList(), intake = EMPTY_INTAKE)
        advanceUntilIdle()
        vm.onOpenFoodPicker(meal)

        vm.onPickFood(rice, rice.servings.first())
        advanceUntilIdle()

        assertEquals(R.string.msg_meal_item_limit, vm.uiState.value.snackbarRes)
        assertEquals(1, vm.uiState.value.snackbarArgs.size)
        assertTrue(vm.uiState.value.snackbarArgs.single().toIntOrNull() != null)
    }

    @Test
    fun deleteItem_removesThatRowOnly() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel(items = listOf(riceItem), intake = EMPTY_INTAKE)
        advanceUntilIdle()

        vm.onDeleteMealItem(riceItem)
        advanceUntilIdle()

        coVerify(exactly = 1) { mealItemRepository.delete(500L) }
    }

    @Test
    fun dismissPicker_keepsAlreadyLoggedItems() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel(items = listOf(riceItem), intake = EMPTY_INTAKE)
        advanceUntilIdle()
        vm.onOpenFoodPicker(meal)

        vm.onDismissFoodPicker()

        assertNull(vm.uiState.value.pickingMealId)
        assertEquals(listOf(riceItem), vm.uiState.value.mealItems)
    }

    // ---------------- 改份量 / 挪餐次 ----------------

    /** 按份记的条目：一档 = 半份，每份克数从条目自己反推（200 g / 1 碗）。 */
    @Test
    fun stepUp_onServedItem_addsHalfServing() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel(items = listOf(riceItem), intake = EMPTY_INTAKE)
        advanceUntilIdle()
        vm.onOpenItemEditor(riceItem)

        vm.onStepItemPortion(up = true)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            changePortion.invoke(
                itemId = 500L,
                serving = FoodServing(unit = "碗", grams = 200),
                servingCount = 1.5,
                grams = null,
            )
        }
    }

    /** 按克记的条目：一档 = 10 g，不碰份。 */
    @Test
    fun stepUp_onGramsOnlyItem_addsTenGrams() = runTest(mainDispatcherRule.testDispatcher) {
        val gramsItem = riceItem.copy(servingUnit = null, servingCount = null, grams = 100.0)
        val vm = newViewModel(items = listOf(gramsItem), intake = EMPTY_INTAKE)
        advanceUntilIdle()
        vm.onOpenItemEditor(gramsItem)

        vm.onStepItemPortion(up = false)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            changePortion.invoke(itemId = 500L, serving = null, servingCount = 1.0, grams = 90.0)
        }
    }

    @Test
    fun stepWithoutEditorOpen_writesNothing() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel(items = listOf(riceItem), intake = EMPTY_INTAKE)
        advanceUntilIdle()

        vm.onStepItemPortion(up = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { changePortion.invoke(any(), any(), any(), any()) }
    }

    @Test
    fun moveToAnotherMeal_forwardsThatItemAndMeal() = runTest(mainDispatcherRule.testDispatcher) {
        val dinner = meal.copy(id = 78L, mealType = MealType.DINNER)
        val vm = newViewModel(items = listOf(riceItem), intake = EMPTY_INTAKE)
        advanceUntilIdle()

        vm.onMoveItemToMeal(riceItem, dinner)
        advanceUntilIdle()

        coVerify(exactly = 1) { mealItemRepository.moveTo(500L, 78L) }
    }

    /** 点到自己那一餐的 chip 不该产生一次"挪动"写入。 */
    @Test
    fun moveToSameMeal_writesNothing() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel(items = listOf(riceItem), intake = EMPTY_INTAKE)
        advanceUntilIdle()

        vm.onMoveItemToMeal(riceItem, meal)
        advanceUntilIdle()

        coVerify(exactly = 0) { mealItemRepository.moveTo(any(), any()) }
    }

    private companion object {
        val EMPTY_INTAKE: MealIntake = MealIntake(0, 0.0, 0.0, 0.0, 0, emptySet())
    }
}
