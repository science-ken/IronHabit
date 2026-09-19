package com.ironhabit.app.ui.screens.today

import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.domain.model.TodayOverview
import com.ironhabit.app.domain.model.TodayMeals
import com.ironhabit.app.domain.model.TodayPlanItem
import com.ironhabit.app.domain.model.BodyReview
import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.model.DietReview
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase
import io.mockk.coEvery
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.StatsRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * Fix2（日期栏接通）行为验证：**跨周/未来日写操作落到哪一天**。
 *
 * 直接驱动生产 [TodayViewModel]（未改动），只 mock 其依赖的 UseCase / Repository，
 * 断言「切换到未来日」后各写入口传给用例的 `epochDay` 到底是「所选日」还是「今天」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelDateCursorTest {

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
    private val checkInRepository = mockk<CheckInRepository>(relaxed = true)
    private val planRepository = mockk<PlanRepository>(relaxed = true)
    private val buildWeeklyReview = mockk<BuildWeeklyReviewUseCase>()
    private val statsRepository = mockk<StatsRepository>(relaxed = true)

    /** 本周热力：周一到周日 7 格，全 0 档（本用例只验证它能不能到 uiState）。 */
    private fun weekHeat(): List<HeatmapCell> = (0L until 7L).map { offset ->
        HeatmapCell(epochDay = 20_710L + offset, count = 0, level = 0)
    }

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
    private val futureDay: Long = today + 7 // 「看下周计划」

    private val exercise = Exercise(id = 1L, name = "卧推", category = ExerciseCategory.STRENGTH)
    private val plan = WeekPlan(id = 10L, exerciseId = 1L, dayOfWeek = 1, targetSets = 3, targetReps = 10)
    private val planItem = TodayPlanItem(plan = plan, exercise = exercise, isCompleted = false)
    private val habitItem = HabitItem(habit = Habit(id = 5L, name = "喝水"), isCompletedToday = false)

    private fun newViewModel(): TodayViewModel {
        // 日期游标驱动聚合视图：overview.dateEpochDay 必须回显被请求的那一天。
        every { getTodayOverview.invoke(today) } returns flowOf(TodayOverview(dateEpochDay = today))
        every { getTodayOverview.invoke(futureDay) } returns flowOf(TodayOverview(dateEpochDay = futureDay))
        every { getTodayMeals.invoke(today) } returns flowOf(TodayMeals())
        every { getTodayMeals.invoke(futureDay) } returns flowOf(TodayMeals())
        every { planRepository.observePlannedWeekdays() } returns flowOf(emptyList())
        // P3：今日页会读「每周相同」那份是否存在（开关状态）。
        every { planRepository.observeRepeatPlan() } returns flowOf(emptyList())
        every { checkInRepository.observeActiveDaysSince(any()) } returns flowOf(emptyList())
        // 本用例不验证周磁贴：给一份「本周什么都没练」的复盘 → 磁贴整块不渲染。
        coEvery { buildWeeklyReview.invoke(any()) } returns emptyWeekReview()
        coEvery { statsRepository.heatmapRange(any(), any()) } returns weekHeat()

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
            buildWeeklyReview = buildWeeklyReview,
            statsRepository = statsRepository,
            clock = clock,
            timeZone = timeZone,
        )
    }

    @Test
    fun initialCursorIsToday() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals("默认日期游标 = 今天", today, vm.uiState.value.dateEpochDay)
        assertEquals("todayEpochDay 与游标分离（高亮用）", today, vm.uiState.value.todayEpochDay)
    }

    /**
     * 回归：`applyData` 逐字段搬运，漏掉 `weeklyReview` 时「本周」磁贴在**任何一周都不出现**
     * （真机实测踩到，与 `isRepeatWeeklyOn` 是同一类坑 —— 数据流算好了却没落到 `_uiState`）。
     */
    @Test
    fun weeklyReviewReachesUiState() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        assertNotNull(
            "周复盘必须搬进 uiState，否则本周磁贴永远拿不到数据",
            vm.uiState.value.weeklyReview,
        )
        assertEquals(
            "本周热力同样要逐字段搬进 uiState（applyData 漏一个字段那格就永远是初始值）",
            7,
            vm.uiState.value.weekHeatmap.size,
        )
    }

    @Test
    fun selectingFutureDayMovesCursor() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onSelectEpochDay(futureDay)
        advanceUntilIdle()

        assertEquals("切换后日期游标 = 所选未来日", futureDay, vm.uiState.value.dateEpochDay)
        assertEquals("todayEpochDay 仍为真正今天（游标与今天分离）", today, vm.uiState.value.todayEpochDay)
    }

    @Test
    fun writeOpsAfterSelectingFutureDayLandOnSelectedFutureDay() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = newViewModel()
            advanceUntilIdle()

            vm.onSelectEpochDay(futureDay)
            advanceUntilIdle()

            // 用户在「看下周计划」页面上顺手做了这些操作：
            vm.onToggleSet(planItem, setIndex = 0)
            vm.onSetRpe(planItem, rpe = 8)
            vm.onUndoCheckIn(planItem)
            vm.onToggleHabit(habitItem)
            vm.onDetailedCheckIn(planItem, sets = 5, reps = 10, weightKg = null, durationMinutes = null, notes = null)
            advanceUntilIdle()

            coVerify { toggleSet(exerciseId = 1L, epochDay = futureDay, setIndex = 0) }
            coVerify { setRpe(exerciseId = 1L, epochDay = futureDay, rpe = 8) }
            coVerify { undoCheckIn(exerciseId = 1L, epochDay = futureDay) }
            coVerify { toggleHabit(habitId = 5L, epochDay = futureDay, done = true) }
            coVerify {
                detailedCheckIn.invoke(
                    exerciseId = 1L,
                    planId = 10L,
                    epochDay = futureDay,
                    sets = 5,
                    reps = 10,
                    weightKg = null,
                    durationMinutes = null,
                    notes = null,
                )
            }
        }

    /**
     * 写入口径统一（Round-3）：一键打卡与其他写操作一样，把**所选日**（`currentEpochDay()`）
     * 转发给 `QuickCheckInUseCase`，不再由用例内部自算「今天」。
     *
     * 注：本条只验证 ViewModel 的**转发口径**。未来日的**可写性**由 UI 层 `enabled` 门控
     * （见 `TodayScreen`/`ExerciseCheckCard` 的 `isFutureDay`），ViewModel 不做拦截。
     */
    @Test
    fun quickCheckInForwardsSelectedDayToUseCase() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = newViewModel()
            advanceUntilIdle()

            vm.onSelectEpochDay(futureDay)
            advanceUntilIdle()

            vm.onQuickCheckIn(planItem)
            advanceUntilIdle()

            // 一键打卡把「所选日」转发给用例（口径与逐组/RPE/撤销/习惯/补录一致）。
            coVerify(exactly = 1) { quickCheckIn.invoke(plan, futureDay) }
        }
}
