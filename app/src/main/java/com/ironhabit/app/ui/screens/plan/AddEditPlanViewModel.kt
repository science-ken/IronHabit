package com.ironhabit.app.ui.screens.plan

import androidx.annotation.StringRes
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.ui.navigation.Destinations
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 「新增 / 编辑计划条目」UI 状态（不可变）。
 *
 * @property isLoading 首帧加载中
 * @property isEditing `true` = 编辑已有计划，`false` = 新增
 * @property exercises 可选动作（启用动作库）
 * @property selectedExerciseId 已选动作 id（`0` = 未选）
 * @property dayOfWeek 星期（`1` = 周一 … `7` = 周日）
 * @property targetSets 目标组数（文本）
 * @property targetReps 目标每组次数（文本）
 * @property targetWeightKg 目标重量 kg（文本，可空）
 * @property targetDurationMin 目标时长分钟（文本，可空）
 * @property exerciseErrorRes 未选动作的错误资源 id
 * @property numberErrorRes 数字校验错误资源 id
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性 Snackbar 资源 id
 * @property isSaving 写入中（P0-4 防抖：进入即置位，按钮据此禁用，避免连点产生重复数据）
 * @property saved 保存成功标志
 */
data class AddEditPlanUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val exercises: List<Exercise> = emptyList(),
    val selectedExerciseId: Long = 0L,
    val dayOfWeek: Int = 1,
    val targetSets: String = DEFAULT_SETS_TEXT,
    val targetReps: String = DEFAULT_REPS_TEXT,
    val targetWeightKg: String = "",
    val targetDurationMin: String = "",
    @StringRes val exerciseErrorRes: Int? = null,
    @StringRes val numberErrorRes: Int? = null,
    @StringRes val errorRes: Int? = null,
    @StringRes val snackbarRes: Int? = null,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
)

private const val DEFAULT_SETS_TEXT = "3"
private const val DEFAULT_REPS_TEXT = "12"

/**
 * 「新增 / 编辑计划条目」ViewModel。
 *
 * 路由参数：`planId`（`0` = 新增）+ `dayOfWeek`（`1..7`，新增时的默认星期）。
 * 可选动作来自 [ExerciseRepository.observeActive]；编辑态从 [PlanRepository.observeAll] 中定位旧值。
 */
@HiltViewModel
class AddEditPlanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    exerciseRepository: ExerciseRepository,
    private val planRepository: PlanRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    /** 路由参数：`0` 表示新增。 */
    private val planId: Long = savedStateHandle.get<Long>(Destinations.PLAN_ARG_ID) ?: 0L

    /** 路由参数：新增时的默认星期（`1..7`）。 */
    private val initialDay: Int =
        (savedStateHandle.get<Int>(Destinations.PLAN_ARG_DAY) ?: DEFAULT_DAY)
            .coerceIn(MIN_DAY, MAX_DAY)

    private val _form = MutableStateFlow(
        AddEditPlanUiState(isEditing = planId != 0L, dayOfWeek = initialDay),
    )

    /** 对外状态：动作列表来自 Room，表单字段来自 [_form]。 */
    val uiState: StateFlow<AddEditPlanUiState> =
        combine(exerciseRepository.observeActive(), _form) { exercises, form ->
            form.copy(exercises = exercises)
        }
            .catch {
                emit(AddEditPlanUiState(isLoading = false, errorRes = R.string.error_load_failed))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = AddEditPlanUiState(isEditing = planId != 0L, dayOfWeek = initialDay),
            )

    init {
        loadExistingPlan()
    }

    /** 编辑态：定位旧计划并填充表单；新增态直接进入可编辑。 */
    private fun loadExistingPlan() {
        if (planId == 0L) {
            _form.update { it.copy(isLoading = false, isEditing = false) }
            return
        }
        viewModelScope.launch {
            runCatching { planRepository.observeAll().first().firstOrNull { it.id == planId } }
                .onSuccess { plan ->
                    if (plan == null) {
                        _form.update {
                            it.copy(isLoading = false, errorRes = R.string.error_load_failed)
                        }
                    } else {
                        _form.update {
                            it.copy(
                                isLoading = false,
                                isEditing = true,
                                selectedExerciseId = plan.exerciseId,
                                dayOfWeek = plan.dayOfWeek.coerceIn(MIN_DAY, MAX_DAY),
                                targetSets = plan.targetSets.toString(),
                                targetReps = plan.targetReps.toString(),
                                targetWeightKg = plan.targetWeightKg?.toString().orEmpty(),
                                targetDurationMin = plan.targetDurationMin?.toString().orEmpty(),
                            )
                        }
                    }
                }
                .onFailure {
                    _form.update {
                        it.copy(isLoading = false, errorRes = R.string.error_load_failed)
                    }
                }
        }
    }

    fun onSelectExercise(exerciseId: Long) =
        _form.update { it.copy(selectedExerciseId = exerciseId, exerciseErrorRes = null) }

    fun onSelectDay(dayOfWeek: Int) =
        _form.update { it.copy(dayOfWeek = dayOfWeek.coerceIn(MIN_DAY, MAX_DAY)) }

    fun onTargetSetsChange(value: String) =
        _form.update { it.copy(targetSets = value, numberErrorRes = null) }

    fun onTargetRepsChange(value: String) =
        _form.update { it.copy(targetReps = value, numberErrorRes = null) }

    fun onTargetWeightChange(value: String) =
        _form.update { it.copy(targetWeightKg = value, numberErrorRes = null) }

    fun onTargetDurationChange(value: String) =
        _form.update { it.copy(targetDurationMin = value, numberErrorRes = null) }

    fun onConsumeSnackbar() = _form.update { it.copy(snackbarRes = null) }

    fun onRetry() {
        _form.update { it.copy(isLoading = true, errorRes = null, saved = false) }
        loadExistingPlan()
    }

    /**
     * 校验并保存计划条目。
     *
     * 数值范围以 [InputLimits] 为唯一真源：`targetSets` `1..31`（= 打卡位图位宽）、
     * `targetReps` `1..100`、`targetWeightKg` `0..500`、`targetDurationMin` `1..600`。
     * 「不是数字」与「越界」共用同一条提示（[R.string.error_invalid_number]，不新增资源）。
     */
    fun onSave() {
        val state = _form.value
        if (state.selectedExerciseId == 0L) {
            _form.update { it.copy(exerciseErrorRes = R.string.hint_pick_exercise) }
            return
        }
        val sets = state.targetSets.trim().toIntOrNull()
        val reps = state.targetReps.trim().toIntOrNull()
        val weightInput = state.targetWeightKg.trim()
        val weight = weightInput.toFloatOrNull()
        val durationInput = state.targetDurationMin.trim()
        val duration = durationInput.toIntOrNull()

        // 留空 = 不设该目标（合法，落 null）；填了就必须落在范围内，越界一律拒绝写入。
        val weightValid = weightInput.isEmpty() || (weight != null && InputLimits.isValidWeightKg(weight))
        val durationValid =
            durationInput.isEmpty() || (duration != null && InputLimits.isValidDurationMin(duration))
        if (sets == null || !InputLimits.isValidSets(sets) ||
            reps == null || !InputLimits.isValidReps(reps) ||
            !weightValid || !durationValid
        ) {
            _form.update { it.copy(numberErrorRes = R.string.error_invalid_number) }
            return
        }

        // 🔒 P0-4 防抖：连点「保存」会读到同一个 `planId = 0` → 同一条计划被插两次。
        if (_form.value.isSaving) return
        _form.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val existing = if (planId != 0L) {
                    planRepository.observeAll().first().firstOrNull { it.id == planId }
                } else {
                    null
                }
                val nowMillis = clock.now().toEpochMilliseconds()
                val plan = WeekPlan(
                    id = existing?.id ?: 0L,
                    exerciseId = state.selectedExerciseId,
                    dayOfWeek = state.dayOfWeek,
                    targetSets = sets,
                    targetReps = reps,
                    targetWeightKg = weight,
                    targetDurationMin = duration,
                    sortOrder = existing?.sortOrder ?: 0,
                    isActive = existing?.isActive ?: true,
                    // 🔒 P3：计划按周存放 —— 手动新增/编辑落到**当前这一周**。
                    // 不带这一维就会落到 `0`（=「每周相同」那份），于是"我在下周加一个动作"
                    // 会变成"以后每周都多这个动作"，而且用户在"这一周"里根本看不到它。
                    // 编辑已有行时沿用该行自己的周，避免把行"搬"到别的周。
                    weekStartEpochDay = existing?.weekStartEpochDay
                        ?: DateUtils.weekStartMon1(DateUtils.todayEpochDay(clock, timeZone)),
                    createdAt = existing?.createdAt?.takeIf { it > 0L } ?: nowMillis,
                )
                planRepository.upsert(plan)
                _form.update { it.copy(snackbarRes = R.string.msg_saved, saved = true) }
            } catch (throwable: Throwable) {
                // 改动作/星期时若目标 (天,动作) 槽位已被另一条计划占用 → Room 抛 UNIQUE 冲突，
                // 给更精准的提示而非笼统"保存失败"。
                val res = if (throwable is android.database.sqlite.SQLiteConstraintException) {
                    R.string.error_duplicate_plan
                } else {
                    R.string.error_save_failed
                }
                _form.update { it.copy(snackbarRes = res) }
            } finally {
                // 必须复位：否则保存失败后按钮永久禁用，用户只能退出页面重来。
                _form.update { it.copy(isSaving = false) }
            }
        }
    }

    private companion object {
        const val DEFAULT_DAY = 1
        const val MIN_DAY = 1
        const val MAX_DAY = 7
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
