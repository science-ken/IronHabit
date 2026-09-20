package com.ironhabit.app.ui.screens.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.usecase.AddExerciseToPlanUseCase
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 「训练」页 ViewModel（周计划 / 动作库 / 历史 三分段共用）。
 *
 * 动作库（v6）：动作不再有"启用 / 停用"开关 —— 行尾是「加入计划」的 `+` /
 * 已加入的 `✓`，落库走 [AddExerciseToPlanUseCase]（用户显式路径）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrainViewModel @Inject constructor(
    private val planRepository: PlanRepository,
    private val resetPlanItem: ResetPlanItemUseCase,
    private val exerciseRepository: ExerciseRepository,
    private val checkInRepository: CheckInRepository,
    private val addToPlan: AddExerciseToPlanUseCase,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrainUiState())
    val uiState: StateFlow<TrainUiState> = _uiState.asStateFlow()

    /** 当前选中星期（`1..7`）。 */
    private val selectedDay = MutableStateFlow(todayWeekday())

    /** 数据流重订阅触发器（失败重试）：自增即让下面的聚合流整体重订阅一次。 */
    private val retryTrigger = MutableStateFlow(0L)

    /** 本周的周一（P3：计划按周存放；动作库「+」默认写这一周）。 */
    private val currentWeekStart: Long = DateUtils.weekStartMon1(todayEpochDay())

    /** 所选星期的计划（携带 day 以便区分新旧）。 */
    private val plansFlow: Flow<Pair<Int, List<WeekPlan>>> =
        selectedDay.flatMapLatest { day ->
            planRepository.observePlansForDay(day).map { plans -> day to plans }
        }

    /** 本周生效计划（专属优先，回落「每周相同」）→ `exerciseId → 出现的星期集合`。 */
    private val plannedDaysFlow: Flow<Map<Long, Set<Int>>> =
        planRepository.observeEffectivePlanForWeek(currentWeekStart)
            .map { plans -> plans.toDaysByExercise() }

    /** 「每周相同」那份 → `exerciseId → 出现的星期集合`（弹层「每周都加」勾选初值参考）。 */
    private val repeatDaysFlow: Flow<Map<Long, Set<Int>>> =
        planRepository.observeRepeatPlan()
            .map { plans -> plans.toDaysByExercise() }

    /** 近 30 天打卡历史（按日期倒序聚合为 [HistoryEntry]）。 */
    private val historyFlow: Flow<List<HistoryEntry>> =
        checkInRepository.observeBetween(historyStartEpochDay(), historyEndEpochDay())
            .map { checkIns ->
                checkIns
                    .groupBy { it.dateEpochDay }
                    .map { (epochDay, items) -> HistoryEntry(epochDay = epochDay, count = items.size) }
                    .sortedByDescending { it.epochDay }
            }

    private val dataState: StateFlow<TrainUiState> = retryTrigger
        .flatMapLatest {
            combine(
                plansFlow,
                exerciseRepository.observeActive(),
                historyFlow,
                plannedDaysFlow,
                repeatDaysFlow,
            ) { dayPlans, exercises, history, plannedDays, repeatDays ->
                TrainUiState(
                    isLoading = false,
                    selectedDay = dayPlans.first,
                    weekStartEpochDay = currentWeekStart,
                    plans = dayPlans.second,
                    exercises = exercises,
                    exerciseNameById = exercises.associate { exercise -> exercise.id to exercise.name },
                    history = history,
                    plannedDaysByExercise = plannedDays,
                    repeatDaysByExercise = repeatDays,
                )
            }
                // 每次（重）订阅都先发一帧「加载中」：否则重试再次失败时，
                // 与已缓存的错误态完全相同的值会被 StateFlow 去重丢掉，界面会永远卡在重试前的状态。
                .onStart { emit(TrainUiState()) }
                .catch {
                    emit(TrainUiState(isLoading = false, errorRes = R.string.error_load_failed))
                }
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

    // ---------------- 动作库「加入计划」（v6）----------------

    /** 打开某动作的「加入计划」弹层。 */
    fun onOpenAddToPlanSheet(exercise: Exercise) {
        _uiState.update { state -> state.copy(addToPlanSheetExercise = exercise) }
    }

    /** 关闭「加入计划」弹层（不写任何数据）。 */
    fun onDismissAddToPlanSheet() {
        _uiState.update { state -> state.copy(addToPlanSheetExercise = null) }
    }

    /**
     * 提交「加入计划」：以勾选天为目标态落库（写入 / 取消），可选同步「每周相同」。
     *
     * 成功 → 关弹层 + Snackbar；失败 → 关弹层 + 页面级错误（与全局错误风格一致，可重开重试）。
     * 写库期间 [TrainUiState.isSubmittingAdd] = true（确认按钮禁用，防连点）。
     */
    fun onSubmitAddToPlan(
        exerciseId: Long,
        selectedDays: Set<Int>,
        targetSets: Int,
        targetReps: Int,
        targetDurationMin: Int?,
        alsoRepeatWeekly: Boolean,
    ) {
        if (_uiState.value.isSubmittingAdd) return
        viewModelScope.launch {
            _uiState.update { state -> state.copy(isSubmittingAdd = true) }
            try {
                addToPlan(
                    exerciseId = exerciseId,
                    weekStartEpochDay = currentWeekStart,
                    selectedDays = selectedDays,
                    targetSets = targetSets,
                    targetReps = targetReps,
                    targetDurationMin = targetDurationMin,
                    alsoRepeatWeekly = alsoRepeatWeekly,
                )
                _uiState.update { state ->
                    state.copy(
                        isSubmittingAdd = false,
                        addToPlanSheetExercise = null,
                        snackbarRes = R.string.msg_plan_updated,
                    )
                }
            } catch (throwable: Throwable) {
                _uiState.update { state ->
                    state.copy(isSubmittingAdd = false, errorRes = R.string.error_generic)
                }
            }
        }
    }

    /** 消费一次 Snackbar。 */
    fun onConsumeSnackbar() {
        _uiState.update { state -> state.copy(snackbarRes = null) }
    }

    /** 加载失败后重试（重新订阅数据源；Room 数据流本身是响应式的，这里只是把整条聚合流重订一次）。 */
    fun onRetry() {
        _uiState.update { state -> state.copy(isLoading = true, errorRes = null) }
        retryTrigger.update { it + 1L }
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
        addToPlanSheetExercise = local.addToPlanSheetExercise ?: data.addToPlanSheetExercise,
        isSubmittingAdd = local.isSubmittingAdd,
        errorRes = data.errorRes ?: local.errorRes,
    )

    private fun List<WeekPlan>.toDaysByExercise(): Map<Long, Set<Int>> =
        groupBy { it.exerciseId }
            .mapValues { (_, rows) -> rows.map { plan -> plan.dayOfWeek }.toSet() }

    private fun todayWeekday(): Int =
        DateUtils.weekdayMon1(todayEpochDay())

    private fun todayEpochDay(): Long = DateUtils.todayEpochDay(clock, timeZone)

    private fun historyEndEpochDay(): Long = todayEpochDay()

    private fun historyStartEpochDay(): Long = historyEndEpochDay() - (HISTORY_DAYS - 1)

    private companion object {
        const val MIN_DAY_OF_WEEK = 1
        const val MAX_DAY_OF_WEEK = 7
        const val HISTORY_DAYS = 30L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
