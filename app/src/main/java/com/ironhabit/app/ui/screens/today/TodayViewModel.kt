package com.ironhabit.app.ui.screens.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.domain.model.TodayOverview
import com.ironhabit.app.domain.model.TodayPlanItem
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.usecase.DetailedCheckInUseCase
import com.ironhabit.app.domain.usecase.GetTodayOverviewUseCase
import com.ironhabit.app.domain.usecase.QuickCheckInUseCase
import com.ironhabit.app.domain.usecase.ToggleHabitUseCase
import com.ironhabit.app.domain.usecase.UndoCheckInUseCase
import com.ironhabit.app.domain.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 「今日」页 ViewModel。
 *
 * 数据源：以 [CheckInRepository.observeActiveDaysSince]（传 `0L` 取全量降序）作为**变更触发器**，
 * `flatMapLatest { getTodayOverview(今天) }` 组装聚合视图。任何写库（打卡/撤销/勾选）都会让 Room 重发射，
 * 于是卡片自动置灰、进度环自动前进——**UI 无需手动刷新**。
 *
 * 采用 `stateIn(viewModelScope, WhileSubscribed(5_000), initial)` 把数据流转为 StateFlow，
 * 再合并到内部 [MutableStateFlow]（承载一次性 Snackbar / 错误覆盖），对外只暴露 `asStateFlow()`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val getTodayOverview: GetTodayOverviewUseCase,
    private val quickCheckIn: QuickCheckInUseCase,
    private val detailedCheckIn: DetailedCheckInUseCase,
    private val undoCheckIn: UndoCheckInUseCase,
    private val toggleHabit: ToggleHabitUseCase,
    private val checkInRepository: CheckInRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    /** 对外状态：数据字段来自 [overviewState]，瞬态字段（Snackbar/错误）由动作写入。 */
    private val _uiState = MutableStateFlow(TodayUiState())

    /** 数据流（Room 触发 → 聚合视图 → UiState），按 `stateIn` 转为冷启动的 StateFlow；支持手动重试。 */
    private val retryTrigger = MutableStateFlow(0L)

    private val overviewState: StateFlow<TodayUiState> =
        retryTrigger
            .flatMapLatest {
                checkInRepository.observeActiveDaysSince(TRIGGER_SINCE_EPOCH_DAY)
                    .flatMapLatest { getTodayOverview(todayEpochDay()) }
                    .map { overview: TodayOverview -> overview.toUiState() }
                    .catch {
                        // 加载失败：给页面级错误态，不再向上抛
                        emit(TodayUiState(isLoading = false, errorRes = R.string.error_load_failed))
                    }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = TodayUiState(),
            )

    val uiState: StateFlow<TodayUiState> = _uiState.asStateFlow()

    /** 上一次已上报的 streak，用于检测「涨了 / 断档」。`null` 表示尚未首帧加载。 */
    private var previousStreak: Int? = null

    init {
        // 把数据流合并进对外状态：数据字段覆盖，瞬态字段保留
        viewModelScope.launch {
            overviewState.collect { data -> applyData(data) }
        }
    }

    /** 一键打卡。 */
    fun onQuickCheckIn(item: TodayPlanItem) {
        performWrite(
            baseSnackbarRes = R.string.msg_checkin_done,
            block = { quickCheckIn(item.plan) },
        )
    }

    /** 补录详情提交。 */
    fun onDetailedCheckIn(
        item: TodayPlanItem,
        sets: Int,
        reps: Int,
        weightKg: Float?,
        durationMinutes: Int?,
        notes: String?,
    ) {
        performWrite(
            baseSnackbarRes = R.string.msg_checkin_done,
            block = {
                detailedCheckIn(
                    exerciseId = item.exercise.id,
                    planId = item.plan.id.takeIf { it > 0L },
                    epochDay = _uiState.value.dateEpochDay.let { if (it > 0L) it else todayEpochDay() },
                    sets = sets,
                    reps = reps,
                    weightKg = weightKg,
                    durationMinutes = durationMinutes,
                    notes = notes,
                )
            },
        )
    }

    /** 撤销打卡。 */
    fun onUndoCheckIn(item: TodayPlanItem) {
        performWrite(
            baseSnackbarRes = R.string.msg_undo_done,
            block = {
                undoCheckIn(
                    exerciseId = item.exercise.id,
                    epochDay = _uiState.value.dateEpochDay.let { if (it > 0L) it else todayEpochDay() },
                )
            },
        )
    }

    /** 勾选 / 取消习惯。 */
    fun onToggleHabit(item: HabitItem) {
        performWrite(
            baseSnackbarRes = R.string.msg_saved,
            block = {
                toggleHabit(
                    habitId = item.habit.id,
                    epochDay = _uiState.value.dateEpochDay.let { if (it > 0L) it else todayEpochDay() },
                    done = !item.isCompletedToday,
                )
            },
        )
    }

    /** 消费一次 Snackbar（弹完后由 UI 调用）。 */
    fun onSnackbarShown() {
        _uiState.update { state ->
            state.copy(snackbarRes = null, snackbarArgs = emptyList())
        }
    }

    /** 加载失败后重试（重新订阅数据源）。 */
    fun onRetry() {
        _uiState.update { state -> state.copy(isLoading = true, errorRes = null) }
        retryTrigger.update { it + 1L }
    }

    // ---------------- 内部 ----------------

    /** 统一写操作包装：try/catch 兜底，成功给 Snackbar，失败给 error_generic。 */
    private fun performWrite(baseSnackbarRes: Int, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _uiState.update { state ->
                    state.copy(snackbarRes = baseSnackbarRes, snackbarArgs = emptyList())
                }
            } catch (throwable: Throwable) {
                _uiState.update { state -> state.copy(errorRes = R.string.error_generic) }
            }
        }
    }

    /** 合并数据流：数据字段整体覆盖；Snackbar 由 streak 变化或写操作决定；错误优先取数据流的。 */
    private fun applyData(data: TodayUiState) {
        if (data.isLoading) {
            _uiState.update { state -> state.copy(isLoading = true) }
            return
        }

        if (data.errorRes != null) {
            _uiState.update { state ->
                state.copy(isLoading = false, errorRes = data.errorRes)
            }
            return
        }

        val current: Int = data.trainingStreak.current
        val previous: Int? = previousStreak
        val streakRes: Int? = when {
            previous == null -> null
            current > previous -> R.string.msg_streak_up
            previous > 0 && current == 0 -> R.string.msg_streak_broken
            else -> null
        }

        _uiState.update { state ->
            state.copy(
                isLoading = false,
                dateEpochDay = data.dateEpochDay,
                plans = data.plans,
                habits = data.habits,
                completedCount = data.completedCount,
                totalCount = data.totalCount,
                trainingStreak = data.trainingStreak,
                isRestDay = data.isRestDay,
                errorRes = null,
                snackbarRes = streakRes ?: state.snackbarRes,
                snackbarArgs = if (streakRes == R.string.msg_streak_up) {
                    listOf(current.toString())
                } else {
                    state.snackbarArgs
                },
            )
        }
        previousStreak = current
    }

    private fun todayEpochDay(): Long = DateUtils.todayEpochDay(clock, timeZone)

    private companion object {
        /** 触发器口径：取全历史活跃日（自 1970-01-01 起）。 */
        const val TRIGGER_SINCE_EPOCH_DAY: Long = 0L

        /** 无订阅者后保留缓存 5 秒，避免旋转/切页立即重查。 */
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}

/** [TodayOverview] → [TodayUiState]（仅数据字段；瞬态字段由 ViewModel 维护）。 */
private fun TodayOverview.toUiState(): TodayUiState = TodayUiState(
    isLoading = false,
    dateEpochDay = dateEpochDay,
    plans = plans,
    habits = habits,
    completedCount = completedCount,
    totalCount = totalCount,
    trainingStreak = trainingStreak,
    isRestDay = plans.isEmpty() && habits.isEmpty(),
)
