package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.FoodNutrition
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealItem
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.MealItemRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.util.DateUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CoachContext] 里的**饮食两个数字分别从哪来**（第 3 刀）。
 *
 * 发给模型的 `intakeKcal` 必须是"真吃进去的"，`planKcal` 才是"排了多少"。
 * 这条一旦写反，AI 教练会把一份没执行的计划当成事实来提建议 —— 而界面上什么都看不出来，
 * 因为两个数字都"有值"。所以这里三种状态各钉一条，缺一条都不足以挡住回归。
 */
class BuildCoachContextUseCaseTest {

    private val today: Long = DateUtils.todayEpochDay(FIXED_CLOCK, TimeZone.UTC)

    private val settingsRepository = mockk<SettingsRepository>()
    private val planRepository = mockk<PlanRepository>()
    private val exerciseRepository = mockk<ExerciseRepository>()
    private val checkInRepository = mockk<CheckInRepository>()
    private val bodyMetricRepository = mockk<BodyMetricRepository>()
    private val mealRepository = mockk<MealRepository>()
    private val mealItemRepository = mockk<MealItemRepository>()

    private fun meal(id: Long, kcal: Int, isCompleted: Boolean, isActive: Boolean = true): Meal = Meal(
        id = id,
        dateEpochDay = today,
        mealType = MealType.LUNCH,
        kcal = kcal,
        proteinG = kcal / 20.0,
        isCompleted = isCompleted,
        isActive = isActive,
    )

    private fun item(mealId: Long, kcal: Int): MealItem = MealItem(
        id = 900L + mealId,
        mealId = mealId,
        foodName = "菠菜",
        grams = 200.0,
        nutrition = FoodNutrition(kcal = kcal, proteinG = kcal / 10.0, carbsG = 0.0, fatG = 0.0),
    )

    private fun stub(meals: List<Meal>, items: List<MealItem>) {
        every { settingsRepository.profile() } returns flowOf(UserProfile())
        every { planRepository.observeAll() } returns flowOf(emptyList())
        // 连续天数问的是"本周排了哪几天"，走的是生效行（含模板回落），不是 observeAll。
        every { planRepository.observeEffectivePlanForWeek(any()) } returns flowOf(emptyList())
        every { exerciseRepository.observeActive() } returns flowOf(emptyList())
        every { checkInRepository.observeBetween(any(), any()) } returns flowOf(emptyList())
        every { checkInRepository.observeActiveDaysSince(any()) } returns flowOf(emptyList())
        every { bodyMetricRepository.observeByType(BodyMetricType.WEIGHT) } returns flowOf(emptyList())
        coEvery { mealRepository.getMealsIncludingInactive(any()) } returns meals
        coEvery { mealItemRepository.getByDate(any()) } returns items
    }

    private fun useCase(clock: Clock = FIXED_CLOCK): BuildCoachContextUseCase = BuildCoachContextUseCase(
        settingsRepository = settingsRepository,
        planRepository = planRepository,
        exerciseRepository = exerciseRepository,
        checkInRepository = checkInRepository,
        bodyMetricRepository = bodyMetricRepository,
        mealRepository = mealRepository,
        mealItemRepository = mealItemRepository,
        calculateStreak = CalculateStreakUseCase(clock, TimeZone.UTC),
        clock = clock,
        timeZone = TimeZone.UTC,
    )

    /** 排了餐、一个都没记：计划照报，摄入必须是 0。 */
    @Test
    fun plannedButNeverLogged_sendsZeroIntake() = runTest {
        stub(meals = listOf(meal(id = 1L, kcal = 2200, isCompleted = false)), items = emptyList())

        val context = useCase()()

        assertEquals("没吃就是 0，不能拿计划冒充", 0, context.todayIntakeKcal)
        assertEquals(2200, context.todayPlanKcal)
    }

    /** 只打了勾：取那餐的整餐值（粗记）。 */
    @Test
    fun tickedMealWithoutItems_reportsWholeMealKcal() = runTest {
        stub(meals = listOf(meal(id = 1L, kcal = 737, isCompleted = true)), items = emptyList())

        assertEquals(737, useCase()().todayIntakeKcal)
    }

    /** 打了勾也记了明细：只认明细，整餐值不叠加。 */
    @Test
    fun loggedItemsBeatTheTick() = runTest {
        stub(
            meals = listOf(meal(id = 1L, kcal = 737, isCompleted = true)),
            items = listOf(item(mealId = 1L, kcal = 50)),
        )

        val context = useCase()()

        assertEquals(50, context.todayIntakeKcal)
        assertEquals("分母仍是计划总量，与吃没吃无关", 737, context.todayPlanKcal)
    }

    /** 软删的餐（"这餐不吃"）既不进计划也不进摄入。 */
    @Test
    fun softDeletedMealContributesToNeitherNumber() = runTest {
        stub(
            meals = listOf(
                meal(id = 1L, kcal = 700, isCompleted = false, isActive = false),
                meal(id = 2L, kcal = 500, isCompleted = true),
            ),
            items = emptyList(),
        )

        val context = useCase()()

        assertEquals(500, context.todayIntakeKcal)
        assertEquals(500, context.todayPlanKcal)
    }

    /**
     * 连续天数必须与今日页同一口径（走查 #2）。
     *
     * 排「周一三五」、最近一次打卡在周六、今天是下周一且还没练：
     * 按"每天都该打卡"算 → 中间那个周日没卡就断档 → **0**；
     * 按排期算 → 周日是休息日，既不打断也不计分 → **6**（9/14~9/19 的日历跨度）。
     * 真机上今日页写「连续 6 天」、教练页写「连续 0 天」就是这两条规则打架，
     * 而且那个 0 会跟着 context 一起发给模型 —— AI 会以为用户没坚持。
     */
    @Test
    fun restDaysDoNotBreakTheCoachStreak() = runTest {
        val mondayClock: Clock = clockAt("2026-09-21") // 周一；本周还没练
        stub(meals = emptyList(), items = emptyList())
        every { exerciseRepository.observeActive() } returns flowOf(listOf(exercise(11L)))
        // 本周排「周一 / 周三 / 周五」—— 与今日页取的是同一份生效行
        every { planRepository.observeEffectivePlanForWeek(any()) } returns flowOf(
            listOf(
                plan(weekday = 1, exerciseId = 11L),
                plan(weekday = 3, exerciseId = 11L),
                plan(weekday = 5, exerciseId = 11L),
            ),
        )
        every { checkInRepository.observeActiveDaysSince(any()) } returns flowOf(
            listOf(20715L, 20714L, 20713L, 20712L, 20710L), // 上周六往前数，降序
        )

        assertEquals("休息日不打断：与今日页同一个数", 6, useCase(mondayClock)().currentStreak)
    }

    /** 从没排过计划的人不能被换一套规则：应做日退回"每天都算"，昨天练过就不该归零。 */
    @Test
    fun unplannedUserKeepsTheEveryDayRule() = runTest {
        val mondayClock: Clock = clockAt("2026-09-21")
        stub(meals = emptyList(), items = emptyList())
        every { checkInRepository.observeActiveDaysSince(any()) } returns flowOf(listOf(20716L))

        assertEquals(1, useCase(mondayClock)().currentStreak)
    }

    private fun exercise(id: Long): Exercise = Exercise(id = id, name = "动作$id")

    private fun plan(weekday: Int, exerciseId: Long): WeekPlan = WeekPlan(
        id = exerciseId,
        exerciseId = exerciseId,
        dayOfWeek = weekday,
        targetSets = 3,
        weekStartEpochDay = 0L,
    )

    private fun clockAt(isoDate: String): Clock = object : Clock {
        override fun now(): Instant = Instant.parse("${isoDate}T04:00:00Z")
    }

    private companion object {
        /** 2026-09-20（UTC）—— 与真机验证用的同一天，数字对得上。 */
        val FIXED_CLOCK: Clock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-09-20T04:00:00Z")
        }
    }
}
