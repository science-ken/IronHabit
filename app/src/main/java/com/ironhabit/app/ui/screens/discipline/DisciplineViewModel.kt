package com.ironhabit.app.ui.screens.discipline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.StatsRepository
import com.ironhabit.app.domain.usecase.CalculateStreakUseCase
import com.ironhabit.app.domain.usecase.DeleteHabitUseCase
import com.ironhabit.app.domain.usecase.GetHeatmapUseCase
import com.ironhabit.app.domain.usecase.RestoreHabitUseCase
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
    private val deleteHabit: DeleteHabitUseCase,
    private val restoreHabit: RestoreHabitUseCase,
    private val habitRepository: HabitRepository,
    private val checkInRepository: CheckInRepository,
    private val calculateStreak: CalculateStreakUseCase,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DisciplineUiState())
    val uiState: StateFlow<DisciplineUiState> = _uiState.asStateFlow()

    /** 数据流重订阅触发器（加载失败重试；与 `TodayViewModel` 同构）。 */
    private val retryTrigger = MutableStateFlow(0L)

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
                            // 「每周几」习惯按自身排期计连续；每日习惯传 null（每天都应做）。
                            streak = calculateStreak(activeDays, habit.expectedWeekdays),
                        )
                    }
                }
                combine(itemFlows) { items -> items.toList() }
            }
        }

    /** 软删掉的习惯（`is_active = 0`）。它们不进 [habitItemsFlow]，但必须能在页面上找回来。 */
    private val deletedHabitsFlow: Flow<List<Habit>> =
        habitRepository.observeAllHabits().map { habits -> habits.filter { !it.isActive } }

    /** 热力图（打卡后自动刷新）。 */
    private val heatmapFlow: Flow<List<HeatmapCell>> =
        checkInRepository.observeActiveDaysSince(TRIGGER_SINCE_EPOCH_DAY)
            .map { getHeatmap(HEATMAP_DAYS) }

    /**
     * 本月完成率，外加"这人到底记过没有打卡"。
     *
     * 必须分开带出去：`completionRate` 是 `Float`，`0f` 既可能是"本月一天都没练"，
     * 也可能是"压根还没有数据"。本项目在 `WeeklyReview` / `ProfileLoadPolicy` 等多处立过规矩
     * 「不用 0 冒充 null」，所以界面才不能说「0%」，而该说「还没有数据」。
     */
    private val monthRateFlow: Flow<MonthRate> =
        checkInRepository.observeActiveDaysSince(TRIGGER_SINCE_EPOCH_DAY)
            .map { activeDays ->
                MonthRate(
                    rate = statsRepository.completionRate(monthStartEpochDay(), todayEpochDay()),
                    hasAnyCheckIn = activeDays.isNotEmpty(),
                )
            }

    private val dataState: StateFlow<DisciplineUiState> = retryTrigger
        .flatMapLatest {
            combine(
                habitItemsFlow,
                deletedHabitsFlow,
                heatmapFlow,
                monthRateFlow,
            ) { habits, deletedHabits, heatmap, monthRate ->
                DisciplineUiState(
                    isLoading = false,
                    habits = habits,
                    deletedHabits = deletedHabits,
                    heatmap = heatmap,
                    monthCompletionRate = monthRate.rate,
                    hasAnyCheckIn = monthRate.hasAnyCheckIn,
                )
            }
                .catch {
                    emit(DisciplineUiState(isLoading = false, errorRes = R.string.error_load_failed))
                }
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

    /** 删除习惯（软删除，保留历史日志）。 */
    fun onDeleteHabit(habitId: Long) {
        viewModelScope.launch {
            try {
                deleteHabit(habitId)
                // 删完立刻把「已删除」展开：用户的下一个问题必然是"那它去哪了"，
                // 收起着就等于让他对着一个突然少了一行的列表找不着北。
                _uiState.update { state ->
                    state.copy(snackbarRes = R.string.msg_deleted, showDeleted = true)
                }
            } catch (throwable: Throwable) {
                _uiState.update { state -> state.copy(errorRes = R.string.error_generic) }
            }
        }
    }

    /** 展开 / 收起「已删除 N 条」。 */
    fun onToggleDeleted() {
        _uiState.update { state -> state.copy(showDeleted = !state.showDeleted) }
    }

    /** 恢复一条已删除的习惯（`is_active` 翻回 1，历史日志原样接上）。 */
    fun onRestoreHabit(habitId: Long) {
        viewModelScope.launch {
            try {
                restoreHabit(habitId)
                _uiState.update { state -> state.copy(snackbarRes = R.string.msg_restored) }
            } catch (throwable: Throwable) {
                _uiState.update { state -> state.copy(errorRes = R.string.error_generic) }
            }
        }
    }

    /** 消费一次 Snackbar。 */
    fun onConsumeSnackbar() {
        _uiState.update { state -> state.copy(snackbarRes = null) }
    }

    /**
     * 加载失败重试（错误态里的「重试」按钮）。
     *
     * `habitItemsFlow` / `heatmapFlow` / `monthRateFlow` 都是 Room 冷流，
     * 递增 [retryTrigger] 会让 [dataState] 重新订阅并重跑查询；同时先清掉本地错误态，
     * 否则 [merge] 的 `errorRes = data.errorRes ?: local.errorRes` 会把旧错误一直挂在页面上。
     */
    fun onRetry() {
        _uiState.update { state -> state.copy(errorRes = null) }
        retryTrigger.update { trigger -> trigger + 1L }
    }

    // ---------------- 内部 ----------------

    private fun merge(local: DisciplineUiState, data: DisciplineUiState): DisciplineUiState = data.copy(
        snackbarRes = local.snackbarRes,
        errorRes = data.errorRes ?: local.errorRes,
        // 展开/收起是用户在这一个页面里的临时视角，数据刷新不该把它弹回去。
        showDeleted = local.showDeleted,
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

/** 本月完成率 + 是否存在任何打卡。见 [DisciplineViewModel.monthRateFlow] 为什么要带第二个字段。 */
private data class MonthRate(val rate: Float, val hasAnyCheckIn: Boolean)
