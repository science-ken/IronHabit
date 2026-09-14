package com.ironhabit.app.ui.screens.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.usecase.ResetPlanItemUseCase
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 「训练」页 ViewModel（周计划 / 动作库 / 历史 三分段共用）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrainViewModel @Inject constructor(
    private val planRepository: PlanRepository,
    private val resetPlanItem: ResetPlanItemUseCase,
    private val exerciseRepository: ExerciseRepository,
    private val checkInRepository: CheckInRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrainUiState())
    val uiState: StateFlow<TrainUiState> = _uiState.asStateFlow()

    /** 当前选中星期（`1..7`）。 */
    private val selectedDay = MutableStateFlow(todayWeekday())

    /** 所选星期的计划（携带 day 以便区分新旧）。 */
    private val plansFlow: Flow<Pair<Int, List<WeekPlan>>> =
        selectedDay.flatMapLatest { day ->
            planRepository.observePlansForDay(day).map { plans -> day to plans }
        }

    /** 近 30 天打卡历史（按日期倒序聚合为 [HistoryEntry]）。 */
    private val historyFlow: Flow<List<HistoryEntry>> =
        checkInRepository.observeBetween(historyStartEpochDay(), historyEndEpochDay())
            .map { checkIns ->
                checkIns
                    .groupBy { it.dateEpochDay }
                    .map { (epochDay, items) -> HistoryEntry(epochDay = epochDay, count = items.size) }
                    .sortedByDescending { it.epochDay }
            }

    private val dataState: StateFlow<TrainUiState> = combine(
        plansFlow,
        exerciseRepository.observeActive(),
        historyFlow,
    ) { dayPlans, exercises, history ->
        TrainUiState(
            isLoading = false,
            selectedDay = dayPlans.first,
            plans = dayPlans.second,
            exercises = exercises,
            exerciseNameById = exercises.associate { exercise -> exercise.id to exercise.name },
            history = history,
        )
    }
        .catch {
            emit(TrainUiState(isLoading = false, errorRes = R.string.error_load_failed))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = TrainUiState(),
        )

    init {
        viewModelScope.launch {
            dataState.collect { data -> _uiState.update { local -> merge(local, data) } }
        }
    }

    /** 选择星期（`1..7`）。 */
    fun onSelectDay(dayOfWeek: Int) {
        selectedDay.value = dayOfWeek.coerceIn(MIN_DAY_OF_WEEK, MAX_DAY_OF_WEEK)
    }

    /** 删除计划条目（软删除：`is_active = 0` + `is_user_edited = 1`，阻止 AI 复活）。 */
    fun onDeletePlan(planId: Long) {
        persist(R.string.msg_deleted) { planRepository.delete(planId) }
    }

    /** 恢复为 AI 推荐（`is_user_edited = 0`，交还 AI 接管）。 */
    fun onResetPlan(planId: Long) {
        persist(R.string.msg_saved) { resetPlanItem(planId) }
    }

    /** 启用 / 停用动作。 */
    fun onToggleExerciseActive(exerciseId: Long, active: Boolean) {
        persist(R.string.msg_saved) { exerciseRepository.setActive(exerciseId, active) }
    }

    /** 消费一次 Snackbar。 */
    fun onConsumeSnackbar() {
        _uiState.update { state -> state.copy(snackbarRes = null) }
    }

    // ---------------- 内部 ----------------

    private fun persist(successSnackbarRes: Int, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _uiState.update { state -> state.copy(snackbarRes = successSnackbarRes) }
            } catch (throwable: Throwable) {
                _uiState.update { state -> state.copy(errorRes = R.string.error_generic) }
            }
        }
    }

    private fun merge(local: TrainUiState, data: TrainUiState): TrainUiState = data.copy(
        snackbarRes = local.snackbarRes,
        errorRes = data.errorRes ?: local.errorRes,
    )

    private fun todayWeekday(): Int =
        DateUtils.weekdayMon1(DateUtils.todayEpochDay(clock, timeZone))

    private fun historyEndEpochDay(): Long = DateUtils.todayEpochDay(clock, timeZone)

    private fun historyStartEpochDay(): Long = historyEndEpochDay() - (HISTORY_DAYS - 1)

    private companion object {
        const val MIN_DAY_OF_WEEK = 1
        const val MAX_DAY_OF_WEEK = 7
        const val HISTORY_DAYS = 30L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
