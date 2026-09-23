package com.ironhabit.app.ui.screens.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodServing
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.model.MealItem
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.model.TodayOverview
import com.ironhabit.app.domain.model.TodayPlanItem
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.MealItemRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.StatsRepository
import com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase
import com.ironhabit.app.domain.usecase.AddMealItemResult
import com.ironhabit.app.domain.usecase.AddMealItemUseCase
import com.ironhabit.app.domain.usecase.ChangeMealItemPortionUseCase
import com.ironhabit.app.domain.usecase.DeleteMealUseCase
import com.ironhabit.app.domain.usecase.DetailedCheckInUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
import com.ironhabit.app.domain.usecase.PlanPreview
import com.ironhabit.app.domain.usecase.PlanPreviewHolder
import com.ironhabit.app.domain.usecase.GeneratedPlanSummary
import com.ironhabit.app.domain.usecase.GetTodayMealsUseCase
import com.ironhabit.app.domain.usecase.GetTodayOverviewUseCase
import com.ironhabit.app.domain.usecase.QuickCheckInUseCase
import com.ironhabit.app.domain.usecase.SetRpeUseCase
import com.ironhabit.app.domain.usecase.ToggleHabitUseCase
import com.ironhabit.app.domain.usecase.ToggleMealUseCase
import com.ironhabit.app.domain.usecase.UpsertMealUseCase
import com.ironhabit.app.domain.usecase.ToggleSetUseCase
import com.ironhabit.app.domain.usecase.UndoCheckInUseCase
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.domain.util.TodayClock
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    private val generateTrainingPlan: GenerateTrainingPlanUseCase,
    private val planPreviewHolder: PlanPreviewHolder,
    private val deleteMeal: DeleteMealUseCase,
    private val upsertMeal: UpsertMealUseCase,
    private val addMealItem: AddMealItemUseCase,
    private val changePortion: ChangeMealItemPortionUseCase,
    private val mealItemRepository: MealItemRepository,
    private val checkInRepository: CheckInRepository,
    private val planRepository: PlanRepository,
    private val buildWeeklyReview: BuildWeeklyReviewUseCase,
    private val statsRepository: StatsRepository,
    private val todayClock: TodayClock,
) : ViewModel() {

    /** 对外状态：数据字段来自 [overviewState]，瞬态字段（Snackbar/错误）由动作写入。 */
    private val _uiState = MutableStateFlow(TodayUiState())

    /** 数据流重订阅触发器（失败重试）。 */
    private val retryTrigger = MutableStateFlow(0L)

    /** 日期游标：默认今天；由日期栏 chip / `‹ ›` 跨周改写。 */
    private val selectedEpochDay = MutableStateFlow(todayClock.todayEpochDay())

    /**
     * 用户是否手动挪过游标。挪过之后换天**不再抢** —— 他可能正在回看某一天；
     * 只有停在"今天"时，才跟着时钟推到新的一天。
     */
    private val userMovedCursor = MutableStateFlow(false)

    init {
        // 跨午夜自动跟进：App 常驻开着过 00:00 时，标题仍写「今日」，
        // 而游标停在昨天 —— 一指点下去打卡就写进昨天，streak 与热力图一起被污染。
        // 只靠"数据变化时重算"治不了它：没人写库就没有新发射，所以必须有 [TodayClock] 的轮询。
        viewModelScope.launch {
            todayClock.epochDay
                .drop(1)
                .collect { epochDay ->
                    if (!userMovedCursor.value) selectedEpochDay.value = epochDay
                }
        }
    }

    /** 「以后每周都用这份」那份模板里的启用行数（`0` = 界面上那一行不显示）。 */
    private val repeatPlanCountFlow: Flow<Int> =
        planRepository.observeRepeatPlan().map { plans -> plans.size }

    /** 数据流（Room 触发 → 聚合视图 → UiState），按 `stateIn` 转为冷启动的 StateFlow；支持手动重试。 */
    private val overviewState: StateFlow<TodayUiState> =
        combine(retryTrigger, selectedEpochDay) { _, day -> day }
            .flatMapLatest { day ->
                checkInRepository.observeActiveDaysSince(TRIGGER_SINCE_EPOCH_DAY)
                    .flatMapLatest {
                        // ⚠️ 这里不再额外 combine `planRepository.observePlannedWeekdays()`：
                        // P3 起"哪几天有课"是**按周**算的，`getTodayOverview(day)` 已经带回了
                        // 那一天所在周的结果 —— 再挂一条"当前周"的流会把它覆盖错（翻到下个月就露馅）。
                        combine(
                            getTodayOverview(day),
                            getTodayMeals(day),
                            repeatPlanCountFlow,
                        ) { overview, meals, repeatPlanCount ->
                            // 周复盘挂在同一次触发里取：写库之后整屏（含本周磁贴）一起刷新。
                            // 取整周用 BuildWeeklyReviewUseCase 自己的规则，避免和 AI 教练页口径分叉。
                            val review: WeeklyReview? = weeklyReviewOrNull(
                                BuildWeeklyReviewUseCase.weekStartOf(day),
                            )
                            val heat: List<HeatmapCell> = weekHeatmapOrNull(
                                BuildWeeklyReviewUseCase.weekStartOf(day),
                            )
                            overview.toUiState().copy(
                                meals = meals.meals,
                                mealTotals = meals.totals,
                                mealItems = meals.items,
                                mealIntake = meals.intake,
                                dietTarget = meals.target,
                                repeatPlanRowCount = repeatPlanCount,
                                weeklyReview = review,
                                weekHeatmap = heat,
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

    /**
     * 取某一周的复盘；失败**只让本周那两块磁贴缺席**，不把整页打成错误态。
     *
     * ⚠️ 必须原样重抛 [CancellationException]：`ViewModel` 清理与页面切走都靠它，
     * 一旦被当成普通失败吞掉，协程取消会静默失效。
     */
    private suspend fun weeklyReviewOrNull(weekStartEpochDay: Long): WeeklyReview? =
        try {
            buildWeeklyReview(weekStartEpochDay)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        }

    /** 所选周（周一 → 周日）的热力；失败只让热力条缺席，不影响整页。 */
    private suspend fun weekHeatmapOrNull(weekStartEpochDay: Long): List<HeatmapCell> =
        try {
            statsRepository.heatmapRange(
                startEpochDay = weekStartEpochDay,
                endEpochDay = weekStartEpochDay + WEEK_DAYS - 1L,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            emptyList()
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
        userMovedCursor.value = true
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

    // ---------------- 一餐里"实际吃了什么"的条目 ----------------

    /** 打开食物库**挑选模式**，为哪一餐挑就记进哪一餐。 */
    fun onOpenFoodPicker(meal: Meal) {
        _uiState.update { it.copy(pickingMealId = meal.id) }
    }

    fun onDismissFoodPicker() {
        _uiState.update { it.copy(pickingMealId = null) }
    }

    /**
     * 挑中一样食物 → 记进当前正在挑的那一餐。
     *
     * **不关弹层**：一顿饭通常要记好几样，点一样关一次等于逼用户反复进出。
     * 按克数那条路（[serving] = `null`）先按 100g 起记，真正的克数在条目行上改 ——
     * 这样"两下点完"的路径不会被一个数字键盘堵住。
     */
    fun onPickFood(food: Food, serving: FoodServing?) {
        val mealId: Long = _uiState.value.pickingMealId ?: return
        viewModelScope.launch {
            when (
                addMealItem(
                    mealId = mealId,
                    foodId = food.id,
                    serving = serving,
                    servingCount = 1.0,
                    grams = if (serving == null) DEFAULT_GRAMS_WHEN_NO_SERVING else null,
                )
            ) {
                is AddMealItemResult.Added -> _uiState.update {
                    it.copy(snackbarRes = R.string.msg_meal_item_added, snackbarArgs = emptyList())
                }
                // 到上限：走 %1$s 通道（本项目的 snackbarArgs 恒为 List<String>，
                // 配 %1$d 会抛 IllegalFormatConversionException —— 见契约测试）。
                is AddMealItemResult.MealFull -> _uiState.update {
                    it.copy(
                        snackbarRes = R.string.msg_meal_item_limit,
                        snackbarArgs = listOf(InputLimits.MAX_ITEMS_PER_MEAL.toString()),
                    )
                }
                else -> _uiState.update { state -> state.copy(errorRes = R.string.error_generic) }
            }
        }
    }

    /** 删一条已记条目（撤销误记）。 */
    fun onDeleteMealItem(item: MealItem) {
        performSilentWrite { mealItemRepository.delete(item.id) }
    }

    /** 打开「改这一条」弹层。**不写库**，条目本身从 `mealItems` 里按 id 现取。 */
    fun onOpenItemEditor(item: MealItem) {
        _uiState.update { it.copy(editingItemId = item.id) }
    }

    fun onDismissItemEditor() {
        _uiState.update { it.copy(editingItemId = null) }
    }

    /**
     * 份量加减一档。
     *
     * 按**记法**决定步长：当时是"几份/几碗"记的就 ±半份（一碗一档太粗，半碗是真实需求），
     * 按克记的就 ±10 g。每一下都立即落库 —— Q11 要的是"当场改、只影响这一次"，
     * 而且磁贴数字必须点一下就动，不能藏在一个"保存"后面。
     */
    fun onStepItemPortion(up: Boolean) {
        val itemId: Long = _uiState.value.editingItemId ?: return
        val item: MealItem = _uiState.value.mealItems.firstOrNull { it.id == itemId } ?: return
        val unit: String = item.servingUnit?.takeIf { it.isNotBlank() } ?: ""
        val count: Double = item.servingCount?.takeIf { it > 0.0 } ?: 0.0

        if (unit.isNotEmpty() && count > 0.0) {
            // 每份多少克从条目自己反推：记的时候已经把它乘进 grams 了。
            val gramsPerServing: Int = (item.grams / count).roundToInt()
            val delta: Double = if (up) SERVING_STEP else -SERVING_STEP
            val next: Double = (count + delta).coerceIn(InputLimits.MIN_SERVINGS, InputLimits.MAX_SERVINGS)
            performSilentWrite {
                changePortion(
                    itemId = itemId,
                    serving = FoodServing(unit = unit, grams = gramsPerServing),
                    servingCount = next,
                )
            }
        } else {
            val delta: Int = if (up) GRAMS_STEP else -GRAMS_STEP
            val next: Double = (item.grams.roundToInt() + delta)
                .coerceIn(InputLimits.MIN_SERVING_GRAMS, InputLimits.MAX_SERVING_GRAMS)
                .toDouble()
            performSilentWrite { changePortion(itemId = itemId, serving = null, grams = next) }
        }
    }

    /** 记错了餐次（早餐记成午餐）：只挪归属，快照与时间一律不动。 */
    fun onMoveItemToMeal(item: MealItem, meal: Meal) {
        if (item.mealId == meal.id) return
        performSilentWrite { mealItemRepository.moveTo(item.id, meal.id) }
    }

    /**
     * 打开「编辑这一餐」弹层（把该餐快照放进 UiState，**不写库**）。
     *
     * 保存走 [onSaveMealEdit]：写库成功后才关闭弹层。
     */
    fun onOpenMealEditor(meal: Meal) {
        _uiState.update { state -> state.copy(editingMeal = meal) }
    }

    /** 关闭编辑弹层（取消编辑，不改任何数据）。 */
    fun onDismissMealEditor() {
        _uiState.update { state -> state.copy(editingMeal = null) }
    }

    /**
     * 保存某一餐的编辑结果（条目 / 热量 / 蛋白质）。
     *
     * - 只对**当前打开的那一餐**（[TodayUiState.editingMeal]）生效，避免 UI 传错 id；
     * - 写入口径 = **所选日**（与逐组 / RPE / 习惯 / 补录一致）；
     * - 走 [UpsertMealUseCase] → 置 `isUserEdited = true`：**重新生成饮食时整行跳过**，
     *   用户改过的分量不会被规则覆盖（红线）；
     * - **成功后才关闭弹层**：失败时弹层留在原地、输入不丢，可直接重试。
     */
    fun onSaveMealEdit(
        mealType: MealType,
        items: List<String>,
        kcal: Int,
        proteinG: Double,
    ) {
        val target: Meal = _uiState.value.editingMeal ?: return
        viewModelScope.launch {
            try {
                upsertMeal(
                    id = target.id,
                    epochDay = currentEpochDay(),
                    mealType = mealType,
                    items = items,
                    kcal = kcal,
                    proteinG = proteinG,
                )
                _uiState.update { state ->
                    state.copy(
                        editingMeal = null,
                        snackbarRes = R.string.msg_meal_saved,
                        snackbarArgs = emptyList(),
                    )
                }
            } catch (throwable: Throwable) {
                _uiState.update { state -> state.copy(errorRes = R.string.error_generic) }
            }
        }
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
                        // 本通道实参是 String（List<String>）→ 资源占位符是 %1$s，与 toString() 一致。
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

    /**
     * **让 AI 给"这一周"生成训练计划**（P3：计划按周存放）。
     *
     * 用户在「今日」页翻到某一周、看到「这一周还没有训练计划」时点这个按钮：
     * 生成的是**那一周**的计划（`weekStartMon1(选中日)`），不是"每周相同"那份。
     *
     * 结果**不直接写库**：算完放进 [PlanPreviewHolder] 并跳「本周计划预览」页，
     * 用户逐天点「采纳这天」才落库（手改行不动、陈旧 AI 行回收由 `commit` 保证）。
     */
    fun onCreatePlanByAi() {
        if (_uiState.value.isCreatingPlan) return
        val targetWeek: Long = DateUtils.weekStartMon1(selectedEpochDay.value)

        viewModelScope.launch {
            _uiState.update { state -> state.copy(isCreatingPlan = true) }
            val snackbarRes: Int?
            val snackbarArgs: List<String>
            try {
                val preview: PlanPreview = generateTrainingPlan.preview(targetWeek)
                if (preview.allDrafts.isEmpty()) {
                    // 一条都没排出来（全被手改行 / 模板整日保护挡住）→ 不去预览页空跑一趟。
                    snackbarRes = R.string.msg_plan_nothing_adoptable
                    snackbarArgs = emptyList()
                } else {
                    planPreviewHolder.set(preview)
                    _uiState.update { state -> state.copy(previewRequested = true) }
                    snackbarRes = null
                    snackbarArgs = emptyList()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // 生成失败要说出来（不能"点了没反应"）：沿用页面级错误通道。
                _uiState.update { state ->
                    state.copy(isCreatingPlan = false, errorRes = R.string.error_generic)
                }
                return@launch
            }
            _uiState.update { state ->
                state.copy(
                    isCreatingPlan = false,
                    snackbarRes = snackbarRes,
                    snackbarArgs = snackbarArgs,
                )
            }
        }
    }

    /** 预览页已经跳过去了，消费掉这次信号（否则回到本页会再跳一次）。 */
    fun onPreviewConsumed() {
        _uiState.update { state -> state.copy(previewRequested = false) }
    }

    /** 游标所在周的周一（不是"今天"那周 —— 日期游标可以拨到别的周）。 */
    private fun cursorWeekStart(): Long = DateUtils.weekStartMon1(selectedEpochDay.value)

    /** 游标那一周的**下一周**周一 = 「复制到下周」的落点。 */
    private fun cursorNextWeekStart(): Long = cursorWeekStart() + DateUtils.DAYS_IN_WEEK

    /**
     * 「复制到下周」：把**这一周**的启用行复制成下周的专属行。
     *
     * 与旧「每周相同」的分别是要害：写进下周 = 只有下周变；写进模板 = 以后每个没单独排课的周都变。
     * 下周已经有自己的行时**先要一次确认**（复制是并集 + 同槽位换内容，
     * 而本应用对"覆盖用户数据"一贯先确认 —— 与身体数据删除、导入确认框同源）。
     */
    fun onCopyToNextWeek() {
        val state = _uiState.value
        if (state.isRepeatActionBusy || state.copyToNextWeekConfirm != null) return
        val nextWeek: Long = cursorNextWeekStart()

        viewModelScope.launch {
            try {
                val nextWeekRows: Int = planRepository.getRowsForWeek(nextWeek).count { plan -> plan.isActive }
                if (nextWeekRows > 0) {
                    _uiState.update { it.copy(copyToNextWeekConfirm = nextWeekRows) }
                } else {
                    copyWeekIntoNextWeek()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _uiState.update { it.copy(errorRes = R.string.error_generic) }
            }
        }
    }

    /** 确认框里点了「复制」。 */
    fun onCopyToNextWeekConfirmed() {
        if (_uiState.value.copyToNextWeekConfirm == null) return
        viewModelScope.launch { copyWeekIntoNextWeek() }
    }

    /** 确认框被划掉 / 点了取消：什么都不写。 */
    fun onCopyToNextWeekCancelled() {
        _uiState.update { state -> state.copy(copyToNextWeekConfirm = null) }
    }

    private suspend fun copyWeekIntoNextWeek() {
        val sourceWeek: Long = cursorWeekStart()
        _uiState.update { state ->
            state.copy(isRepeatActionBusy = true, copyToNextWeekConfirm = null)
        }
        try {
            val copied: Int = planRepository.copyWeekInto(
                sourceWeekStartEpochDay = sourceWeek,
                targetWeekStartEpochDay = sourceWeek + DateUtils.DAYS_IN_WEEK,
            )
            _uiState.update { state -> state.copy(isRepeatActionBusy = false) }
            showRepeatNote(
                if (copied == 0) R.string.msg_copy_next_week_empty else R.string.msg_copy_next_week_done,
                copied,
            )
        } catch (cancellation: CancellationException) {
            _uiState.update { state -> state.copy(isRepeatActionBusy = false) }
            throw cancellation
        } catch (throwable: Throwable) {
            _uiState.update { state ->
                state.copy(isRepeatActionBusy = false, errorRes = R.string.error_generic)
            }
        }
    }

    /**
     * 在**弹层内部**挂一句回执，[REPEAT_NOTE_MS] 后自己消失。
     *
     * 为什么不用现成的 Snackbar 通道：`ModalBottomSheet` 会把全局 Snackbar 整个盖住
     * （本仓库 §7 坑 3），真机复现过一次"写库成功、界面零反馈"。
     * 计时放在这里而不是界面的 `LaunchedEffect` 里，是为了不把 effect 放进
     * `if (sheet 开着)` 这种条件分支 —— 那是 B5/A7 那条"effect 进分支会互相打断"的坑。
     */
    private suspend fun showRepeatNote(res: Int, count: Int) {
        _uiState.update { state ->
            state.copy(repeatNoteRes = res, repeatNoteArgs = listOf(count.toString()))
        }
        delay(REPEAT_NOTE_MS)
        _uiState.update { state ->
            if (state.repeatNoteRes == res) {
                state.copy(repeatNoteRes = null, repeatNoteArgs = emptyList())
            } else {
                state
            }
        }
    }

    /**
     * 停用「以后每周都用这份」那份模板（整体软停用，**不 DELETE**，再勾「每周都加」就回来）。
     *
     * 这是"以后每周自动同一份"这个行为唯一的出口 —— 只要模板还有启用行，
     * 任何没排专属行的周都会回落到它，与某一次复制无关。
     */
    fun onDisableRepeatPlan() {
        if (_uiState.value.isRepeatActionBusy) return
        viewModelScope.launch {
            _uiState.update { state -> state.copy(isRepeatActionBusy = true) }
            try {
                val stopped: Int = planRepository.deactivateRepeatPlan()
                _uiState.update { state -> state.copy(isRepeatActionBusy = false) }
                showRepeatNote(R.string.msg_repeat_plan_stopped, stopped)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _uiState.update { state ->
                    state.copy(isRepeatActionBusy = false, errorRes = R.string.error_generic)
                }
            }
        }
    }

    /** 消费一次 Snackbar（弹完后由 UI 调用）。 */    fun onSnackbarShown() {
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
        _uiState.value.dateEpochDay.let { if (it > 0L) it else todayClock.todayEpochDay() }

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
        // 只报"断档"，不报"涨了"：连续天数本身就是磁贴上的大字，再弹一条属于重复打扰。
        val streakRes: Int? = when {
            previous == null -> null
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
                // ⚠️ 这两个必须一起搬。`applyData` 是逐字段手写复制的，
                // 漏一个就会被静默钉回默认值 —— 表现是"记了条目但数字不动"，
                // 而且编译、单测全绿。本项目已经在这上面栽过三次。
                mealItems = data.mealItems,
                mealIntake = data.mealIntake,
                dietTarget = data.dietTarget,
                completedCount = data.completedCount,
                totalCount = data.totalCount,
                trainingStreak = data.trainingStreak,
                isRestDay = data.isRestDay,
                plannedWeekdays = data.plannedWeekdays,
                selectedWeekStartEpochDay = DateUtils.weekStartMon1(data.dateEpochDay),
                hasPlanThisWeek = data.plannedWeekdays.isNotEmpty(),
                // ⚠️ 这个字段必须一起搬过来：`overviewState` 里算好的值如果不落到 `_uiState`，
                // 「以后每周都用这份」那一行就永远不出现（以前是开关永远显示"关"：点了、库里也写了，
                // 但开关弹回去，看着像"点了没反应"）。
                repeatPlanRowCount = data.repeatPlanRowCount,
                // 同一个坑的第二处：本函数逐字段搬运，漏一个字段那格就永远是初始值。
                // 漏掉它时「本周」磁贴在任何一周都不出现（真机实测踩到）。
                weeklyReview = data.weeklyReview,
                weekHeatmap = data.weekHeatmap,
                // 同一个坑的第三处：漏掉它「总组数」就永远只显示完成数、不显示 / 35。
                plannedSetsThisWeek = data.plannedSetsThisWeek,
                todayEpochDay = todayClock.todayEpochDay(),
                errorRes = null,
                snackbarRes = streakRes ?: state.snackbarRes,
                snackbarArgs = state.snackbarArgs,
            )
        }
        previousStreak = current
    }


    private companion object {
        /** 触发器口径：取全历史活跃日（自 1970-01-01 起）。 */
        const val TRIGGER_SINCE_EPOCH_DAY: Long = 0L

        /** 「复制到下周 / 停用」那句回执在弹层里停留多久。 */
        const val REPEAT_NOTE_MS: Long = 4_000L

        /** 无订阅者后保留缓存 5 秒，避免旋转/切页立即重查。 */
        const val STOP_TIMEOUT_MS: Long = 5_000L

        /** 热力条一周 7 格。 */
        private const val WEEK_DAYS: Long = 7L

        /**
         * 挑中一个**没有"份"**的食物时先记这么多克。
         *
         * 100g 是这类食物定义本身的基准量 —— 记完用户可以在条目行上改成实际吃的量。
         * 之所以不在这里弹数字键盘：那会把"两下记完一顿"变成"每样都要打一遍数字"。
         */
        private const val DEFAULT_GRAMS_WHEN_NO_SERVING: Double = 100.0

        /** 「改这一条」的一档：按份记的走半份，按克记的走 10 g。 */
        private const val SERVING_STEP: Double = 0.5
        private const val GRAMS_STEP: Int = 10
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
    // 回归修复：必须把"那一周排了哪几天"搬进来，否则日期栏 chip 消失、
    // hasPlanThisWeek 恒为 false（本周明明有课，休息日却显示「这一周还没有训练计划」）。
    plannedWeekdays = plannedWeekdays,
    plannedSetsThisWeek = plannedSetsThisWeek,
)
