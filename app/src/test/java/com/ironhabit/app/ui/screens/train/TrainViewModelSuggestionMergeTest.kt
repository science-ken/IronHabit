package com.ironhabit.app.ui.screens.train

import com.ironhabit.app.domain.model.AdoptResult
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseSuggestion
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.SuggestionReason
import com.ironhabit.app.domain.model.SuggestionResult
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.usecase.AddExerciseToPlanUseCase
import com.ironhabit.app.domain.usecase.ResetPlanItemUseCase
import com.ironhabit.app.domain.usecase.SuggestExercisesUseCase
import com.ironhabit.app.test.MainDispatcherRule
import com.ironhabit.app.test.todayClockFor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 补充动作那五个字段必须活过 [TrainViewModel] 的 `merge()`。
 *
 * `merge(local, data)` 是**逐字段**把 local 的界面态搬进 data 的，漏一个字段
 * 就会在每次 Room 重 emit 时被静默打回默认值 —— 这个坑在本仓库已经咬过三次
 * （`weeklyReview`、`isRepeatWeeklyOn`（现 `repeatPlanRowCount`）、本周热力）。补充动作这五个字段
 * 恰好全是"一次性 suspend 拉的、不在聚合流里"，是最容易被漏的一类。
 *
 * 断言用的值都是**故意选得和默认值不一样**的：来源用 `REMOTE_LLM`（默认 `LOCAL_RULES`）、
 * 回落原因用 `KEY_NOT_CONFIGURED`（默认 `null`）、建议列表非空。
 * 接线一断，这几条立刻变红，而不是"看起来还是对的"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModelSuggestionMergeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_787_000_000_000L)
    }

    private val planRepository = mockk<PlanRepository>(relaxed = true)
    private val checkInRepository = mockk<CheckInRepository>(relaxed = true)
    private val exerciseRepository = mockk<ExerciseRepository>(relaxed = true)
    private val suggestExercises = mockk<SuggestExercisesUseCase>(relaxed = true)
    private val addToPlan = mockk<AddExerciseToPlanUseCase>(relaxed = true)
    private val resetPlanItem = mockk<ResetPlanItemUseCase>(relaxed = true)

    /** 可重 emit 的动作库流：改它就等于让聚合流再产出一帧，从而走一遍 `merge()`。 */
    private val exercisesFlow = MutableStateFlow(
        listOf(Exercise(id = 1L, name = "深蹲", category = ExerciseCategory.STRENGTH)),
    )

    private val suggestion = ExerciseSuggestion(
        name = "保加利亚分腿蹲",
        category = ExerciseCategory.STRENGTH,
        muscleGroups = listOf("腿部"),
        defaultSets = 3,
        defaultReps = 10,
        noteKey = "note_ai_goal_support",
        reason = SuggestionReason.GOAL_SUPPORT,
    )

    private fun newViewModel(): TrainViewModel {
        every { exerciseRepository.observeActive() } returns exercisesFlow
        every { planRepository.observePlansForDay(any()) } returns flowOf(emptyList<WeekPlan>())
        every { planRepository.observeEffectivePlanForWeek(any()) } returns
            flowOf(emptyList<WeekPlan>())
        every { planRepository.observeRepeatPlan() } returns flowOf(emptyList<WeekPlan>())
        every { checkInRepository.observeBetween(any(), any()) } returns flowOf(emptyList<CheckIn>())
        coEvery { suggestExercises.suggest() } returns SuggestionResult(
            suggestions = listOf(suggestion),
            source = AdviceSource.REMOTE_LLM,
            fallbackReason = RemoteFallbackReason.KEY_NOT_CONFIGURED,
        )
        return TrainViewModel(
            planRepository = planRepository,
            resetPlanItem = resetPlanItem,
            exerciseRepository = exerciseRepository,
            checkInRepository = checkInRepository,
            addToPlan = addToPlan,
            suggestExercises = suggestExercises,
            todayClock = todayClockFor(clock, TimeZone.UTC),
        )
    }

    private fun assertSuggestionFieldsWired(vm: TrainViewModel) {
        val state = vm.uiState.value
        assertEquals("建议列表必须还在", listOf(suggestion), state.suggestions)
        assertEquals("来源标注不能被重置回本地", AdviceSource.REMOTE_LLM, state.suggestionSource)
        assertEquals(
            "回落原因不能被重置回 null",
            RemoteFallbackReason.KEY_NOT_CONFIGURED,
            state.suggestionFallbackReason,
        )
    }

    @Test
    fun suggestions_surviveDataReEmit() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.loadSuggestions()
        advanceUntilIdle()
        assertSuggestionFieldsWired(vm)

        // 动作库长了一条 → 聚合流重 emit → merge(local, data) 跑一遍
        exercisesFlow.value = exercisesFlow.value +
            Exercise(id = 2L, name = "卧推", category = ExerciseCategory.STRENGTH)
        advanceUntilIdle()

        assertEquals("聚合流确实又产出了一帧", 2, vm.uiState.value.exercises.size)
        assertSuggestionFieldsWired(vm)
    }

    @Test
    fun adoptedNames_surviveDataReEmit() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { suggestExercises.adopt(suggestion.name) } returns AdoptResult.ADDED
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onAdoptSuggestion(suggestion.name)
        advanceUntilIdle()
        assertTrue(
            "收入过的名字要记下来（按钮要变灰，避免重复写入）",
            suggestion.name in vm.uiState.value.adoptedNames,
        )

        exercisesFlow.value = exercisesFlow.value +
            Exercise(id = 3L, name = "划船", category = ExerciseCategory.STRENGTH)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.exercises.size)
        assertTrue(
            "adoptedNames 也要活过 merge，否则点完「收入」再动一下就又能连点",
            suggestion.name in vm.uiState.value.adoptedNames,
        )
    }
}
