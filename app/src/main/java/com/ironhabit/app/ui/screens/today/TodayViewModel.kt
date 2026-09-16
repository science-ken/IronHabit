package com.ironhabit.app.ui.screens.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.TodayOverview
import com.ironhabit.app.domain.model.TodayPlanItem
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.usecase.DeleteMealUseCase
import com.ironhabit.app.domain.usecase.DetailedCheckInUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.GetTodayMealsUseCase
import com.ironhabit.app.domain.usecase.GetTodayOverviewUseCase
import com.ironhabit.app.domain.usecase.QuickCheckInUseCase
import com.ironhabit.app.domain.usecase.SetRpeUseCase
import com.ironhabit.app.domain.usecase.ToggleHabitUseCase
import com.ironhabit.app.domain.usecase.ToggleMealUseCase
import com.ironhabit.app.domain.usecase.ToggleSetUseCase
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
import kotlinx.coroutines.flow.combine
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
 * 数据源：以 `selectedEpochDay`（日期游标，默认今天）驱动，内部
 * `checkInRepository.observeActiveDaysSince(0L)` 作为**变更触发器**，
 * `flatMapLatest { combine(getTodayOverview(selectedDay), planRepository.observePlannedWeekdays()) }`
 * 组装聚合视图。任何写库（打卡/撤销/逐组勾选/RPE）都会让 Room 重发射，于是卡片、进度环、日期栏自动刷新。
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
    private val toggleSet: ToggleSetUseCase,
    private val setRpe: SetRpeUseCase,
    private val getTodayMeals: GetTodayMealsUseCase,
    private val toggleMeal: ToggleMealUseCase,
    private val generateDietPlan: GenerateDietPlanUseCase,
    private val deleteMeal: DeleteMealUseCase,
    private val checkInRepository: CheckInRepository,
    private val planRepository: PlanRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    /** 对外状态：数据字段来自 [overviewState]，瞬态字段（Snackbar/错误）由动作写入。 */
    private val _uiState = MutableStateFlow(TodayUiState())

    /** 数据流重订阅触发器（失败重试）。 */
    private val retryTrigger = MutableStateFlow(0L)

    /** 日期游标：默认今天；由日期栏 chip / `‹ ›` 跨周改写。 */
    private val selectedEpochDay = MutableStateFlow(todayEpochDay())

    private val plannedWeekdaysFlow = planRepository.observePlannedWeekdays()

    /** 数据流（Room 触发 → 聚合视图 → UiState），按 `stateIn` 转为冷启动的 StateFlow；支持手动重试。 */
    private val overviewState: StateFlow<TodayUiState> =
        combine(retryTrigger, selectedEpochDay) { _, day -> day }
            .flatMapLatest { day ->
                checkInRepository.observeActiveDaysSince(TRIGGER_SINCE_EPOCH_DAY)
                    .flatMapLatest {
                        combine(
                            getTodayOverview(day),
                            plannedWeekdaysFlow,
                            getTodayMeals(day),
                        ) { overview, weekdays, meals ->
                            overview.toUiState().copy(
                                plannedWeekdays = weekdays,
                                meals = meals.meals,
                                mealTotals = meals.totals,
                                dietTarget = meals.target,
                            )
                        }
                    }
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
            // 口径统一：一键打卡跟随**所选日**（与逐组/RPE/撤销/习惯/补录一致），
            // 不再由 QuickCheckInUseCase 内部自算「真实今天」。
            block = { quickCheckIn(item.plan, currentEpochDay()) },
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
                    epochDay = currentEpochDay(),
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
                    epochDay = currentEpochDay(),
                )
            },
        )
    }

    /** 勾选 / 取消某组（v2）。静默写入，不弹 Snackbar（连续点选时避免打扰）。 */
    fun onToggleSet(item: TodayPlanItem, setIndex: Int) {
        performSilentWrite {
            toggleSet(
                exerciseId = item.exercise.id,
                epochDay = currentEpochDay(),
                setIndex = setIndex,
            )
        }
    }

    /** 写入 RPE（v2）。静默写入。 */
    fun onSetRpe(item: TodayPlanItem, rpe: Int) {
        performSilentWrite {
            setRpe(
                exerciseId = item.exercise.id,
                epochDay = currentEpochDay(),
                rpe = rpe,
            )
        }
    }

    /** 切换查看日期（日期栏 chip / `‹ ›` 跨周）。 */
    fun onSelectEpochDay(epochDay: Long) {
        selectedEpochDay.value = epochDay
    }

    /** 勾选 / 取消习惯。 */
    fun onToggleHabit(item: HabitItem) {
        performWrite(
            baseSnackbarRes = R.string.msg_saved,
            block = {
                toggleHabit(
                    habitId = item.habit.id,
                    epochDay = currentEpochDay(),
                    done = !item.isCompletedToday,
                )
            },
        )
    }

    // ---------------- 饮食（v3）----------------

    /** 勾选 / 取消一餐（[done] = 勾选后的目标状态）。静默写入（连续点选时避免打扰）。 */
    fun onToggleMeal(meal: Meal, done: Boolean) {
        performSilentWrite {
            toggleMeal(mealId = meal.id, done = done)
        }
    }

    /** 删除这餐（软删除：`is_active = 0` + `is_user_edited = 1`，不会被重新生成复活）。 */
    fun onDeleteMeal(meal: Meal) {
        performWrite(
            baseSnackbarRes = R.string.msg_meal_removed,
            block = { deleteMeal(meal.id) },
        )
    }

    /**
     * 生成 / 重新生成饮食计划（对应预览 `doDiet()`）。
     *
     * 提示优先级（都走既有 Snackbar 通道，不新造机制；**均为非阻断、不改变生成结果**）：
     * ① 因忌口过滤掉条目（更具体、更诚实）→ `msg_diet_filtered(N)`；
     * ② 用了默认值（档案未填全 / 无体重）→ `profile_incomplete_hint`；
     * ③ 其它 → `msg_diet_generated`。
     */
    fun onGenerateDiet() {
        viewModelScope.launch {
            try {
                val summary = generateDietPlan(currentEpochDay())
                val hintRes: Int
                val hintArgs: List<String>
                when {
                    summary.filteredCount > 0 -> {
                        hintRes = R.string.msg_diet_filtered
                        hintArgs = listOf(summary.filteredCount.toString())
                    }

                    summary.target.usedDefaults -> {
                        hintRes = R.string.profile_incomplete_hint
                        hintArgs = emptyList()
                    }

                    else -> {
                        hintRes = R.string.msg_diet_generated
                        hintArgs = emptyList()
                    }
                }
                _uiState.update { state ->
                    state.copy(snackbarRes = hintRes, snackbarArgs = hintArgs)
                }
            } catch (throwable: Throwable) {
                _uiState.update { state -> state.copy(errorRes = R.string.error_generic) }
            }
        }
    }

    /** 消费一次 Snackbar（弹完后由 UI 调用）。 */
    fun onSnackbarShown() {        _uiState.update { state ->
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

    /** 静默写操作：成功不提示，失败给 error_generic（用于高频、细粒度的逐组/RPE 操作）。 */
    private fun performSilentWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (throwable: Throwable) {
                _uiState.update { state -> state.copy(errorRes = R.string.error_generic) }
            }
        }
    }

    /** 当前写入口径：优先数据流已加载的日期，否则回落到今天。 */
    private fun currentEpochDay(): Long =
        _uiState.value.dateEpochDay.let { if (it > 0L) it else todayEpochDay() }

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
                meals = data.meals,
                mealTotals = data.mealTotals,
                dietTarget = data.dietTarget,
                completedCount = data.completedCount,
                totalCount = data.totalCount,
                trainingStreak = data.trainingStreak,
                isRestDay = data.isRestDay,
                plannedWeekdays = data.plannedWeekdays,
                todayEpochDay = todayEpochDay(),
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
