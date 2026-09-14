package com.ironhabit.app.ui.screens.discipline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.StatsRepository
import com.ironhabit.app.domain.usecase.CalculateStreakUseCase
import com.ironhabit.app.domain.usecase.GetHeatmapUseCase
import com.ironhabit.app.domain.usecase.ToggleHabitUseCase
import com.ironhabit.app.domain.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * 「自律」页 ViewModel：习惯列表 + 热力图 + 本月小结。
 *
 * 热力图与本月完成率以 [CheckInRepository.observeActiveDaysSince]（`0L`）作为**变更触发器**，
 * `map { getHeatmap(90) }` / `map { statsRepository.completionRate(...) }` 保证打卡后自动刷新；
 * 习惯列表的连续天数按习惯逐条计算（`observeActiveHabits` + `observeActiveDays(id)`）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DisciplineViewModel @Inject constructor(
    private val getHeatmap: GetHeatmapUseCase,
    private val statsRepository: StatsRepository,
    private val toggleHabit: ToggleHabitUseCase,
    private val habitRepository: HabitRepository,
    private val checkInRepository: CheckInRepository,
    private val calculateStreak: CalculateStreakUseCase,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DisciplineUiState())
    val uiState: StateFlow<DisciplineUiState> = _uiState.asStateFlow()

    /** 习惯列表 + 每条 streak（`observeActiveDays(id)` → [CalculateStreakUseCase]）。 */
    private val habitItemsFlow: Flow<List<HabitItem>> =
        habitRepository.observeActiveHabits().flatMapLatest { habits ->
            if (habits.isEmpty()) {
                flowOf(emptyList<HabitItem>())
            } else {
                val today = todayEpochDay()
                val itemFlows: List<Flow<HabitItem>> = habits.map { habit ->
                    habitRepository.observeActiveDays(habit.id).map { activeDays ->
                        HabitItem(
                            habit = habit,
                            isCompletedToday = activeDays.firstOrNull() == today,
                            streak = calculateStreak(activeDays),
                        )
                    }
                }
                combine(itemFlows) { items -> items.toList() }
            }
        }

    /** 热力图（打卡后自动刷新）。 */
    private val heatmapFlow: Flow<List<HeatmapCell>> =
        checkInRepository.observeActiveDaysSince(TRIGGER_SINCE_EPOCH_DAY)
            .map { getHeatmap(HEATMAP_DAYS) }

    /** 本月完成率（打卡后自动刷新）。 */
    private val monthRateFlow: Flow<Float> =
        checkInRepository.observeActiveDaysSince(TRIGGER_SINCE_EPOCH_DAY)
            .map { statsRepository.completionRate(monthStartEpochDay(), todayEpochDay()) }

    private val dataState: StateFlow<DisciplineUiState> = combine(
        habitItemsFlow,
        heatmapFlow,
        monthRateFlow,
    ) { habits, heatmap, monthRate ->
        DisciplineUiState(
            isLoading = false,
            habits = habits,
            heatmap = heatmap,
            monthCompletionRate = monthRate,
        )
    }
        .catch {
            emit(DisciplineUiState(isLoading = false, errorRes = R.string.error_load_failed))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = DisciplineUiState(),
        )

    init {
        viewModelScope.launch {
            dataState.collect { data ->
                val today = todayEpochDay()
                _uiState.update { local -> merge(local, data).copy(dateEpochDay = today) }
            }
        }
    }

    /** 勾选 / 取消某习惯某天。 */
    fun onToggle(habitId: Long, epochDay: Long, done: Boolean) {
        viewModelScope.launch {
            try {
                toggleHabit(habitId = habitId, epochDay = epochDay, done = done)
                _uiState.update { state -> state.copy(snackbarRes = R.string.msg_saved) }
            } catch (throwable: Throwable) {
                _uiState.update { state -> state.copy(errorRes = R.string.error_generic) }
            }
        }
    }

    /** 消费一次 Snackbar。 */
    fun onConsumeSnackbar() {
        _uiState.update { state -> state.copy(snackbarRes = null) }
    }

    // ---------------- 内部 ----------------

    private fun merge(local: DisciplineUiState, data: DisciplineUiState): DisciplineUiState = data.copy(
        snackbarRes = local.snackbarRes,
        errorRes = data.errorRes ?: local.errorRes,
    )

    private fun todayEpochDay(): Long = DateUtils.todayEpochDay(clock, timeZone)

    /** 本月 1 日的 epochDay。 */
    private fun monthStartEpochDay(): Long {
        val today = LocalDate.fromEpochDays(todayEpochDay().toInt())
        return LocalDate(today.year, today.monthNumber, FIRST_DAY_OF_MONTH).toEpochDays().toLong()
    }

    private companion object {
        const val TRIGGER_SINCE_EPOCH_DAY: Long = 0L
        const val HEATMAP_DAYS = 90
        const val FIRST_DAY_OF_MONTH = 1
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
