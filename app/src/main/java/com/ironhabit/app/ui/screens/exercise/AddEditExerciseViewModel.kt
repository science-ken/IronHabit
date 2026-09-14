package com.ironhabit.app.ui.screens.exercise

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.ui.navigation.Destinations
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * 「新增 / 编辑动作」UI 状态（不可变）。
 *
 * 表单可编辑字段以 `String` 承载（用户原始输入，便于逐字符校验），
 * 提示类文案一律用资源 id（[nameErrorRes] / [numberErrorRes] / [errorRes] / [snackbarRes]），
 * 由 Compose 侧 `stringResource(...)` 解析（架构 §7.2 / §7.5）。
 *
 * @property isLoading 首帧加载中（编辑态异步读取旧值）
 * @property isEditing `true` = 编辑已有动作，`false` = 新增
 * @property isBuiltIn 内置动作：不允许改名，只允许改默认组数/次数/时长
 * @property name 动作名
 * @property category 分类
 * @property muscleGroup 目标肌群（可空）
 * @property defaultSets 默认组数（文本）
 * @property defaultReps 默认每组次数（文本）
 * @property defaultDurationSec 默认时长秒（文本，可空）
 * @property nameErrorRes 名称校验错误资源 id
 * @property numberErrorRes 数字校验错误资源 id
 * @property errorRes 页面级错误资源 id（读取失败）
 * @property snackbarRes 一次性 Snackbar 资源 id（保存结果）
 * @property saved 保存成功标志（UI 据此 `popBackStack()`）
 */
data class AddEditExerciseUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val isBuiltIn: Boolean = false,
    val name: String = "",
    val category: ExerciseCategory = ExerciseCategory.CUSTOM,
    val muscleGroup: String = "",
    val defaultSets: String = DEFAULT_SETS_TEXT,
    val defaultReps: String = DEFAULT_REPS_TEXT,
    val defaultDurationSec: String = "",
    @StringRes val nameErrorRes: Int? = null,
    @StringRes val numberErrorRes: Int? = null,
    @StringRes val errorRes: Int? = null,
    @StringRes val snackbarRes: Int? = null,
    val saved: Boolean = false,
)

/** 默认组数文本（纯数字，可安全内联）。 */
private const val DEFAULT_SETS_TEXT = "3"

/** 默认次数文本（纯数字，可安全内联）。 */
private const val DEFAULT_REPS_TEXT = "12"

/**
 * 「新增 / 编辑动作」ViewModel。
 *
 * 路由参数 `exerciseId`（0 = 新增）经 [SavedStateHandle] 注入；编辑态一次性读取旧值后转入表单模型。
 */
@HiltViewModel
class AddEditExerciseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val clock: Clock,
) : ViewModel() {

    /** 路由参数：`0` 表示新增。 */
    private val exerciseId: Long =
        savedStateHandle.get<Long>(Destinations.EXERCISE_ADD_EDIT_ARG) ?: 0L

    private val _uiState = MutableStateFlow(AddEditExerciseUiState(isEditing = exerciseId != 0L))
    val uiState: StateFlow<AddEditExerciseUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** 编辑态：读取旧动作填充表单；新增态直接进入可编辑。 */
    private fun load() {
        if (exerciseId == 0L) {
            _uiState.update { it.copy(isLoading = false, isEditing = false) }
            return
        }
        viewModelScope.launch {
            runCatching { exerciseRepository.getById(exerciseId) }
                .onSuccess { exercise ->
                    if (exercise == null) {
                        _uiState.update {
                            it.copy(isLoading = false, errorRes = R.string.error_load_failed)
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = false, isEditing = true).fromExercise(exercise) }
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(isLoading = false, errorRes = R.string.error_load_failed)
                    }
                }
        }
    }

    fun onNameChange(value: String) =
        _uiState.update { it.copy(name = value, nameErrorRes = null) }

    fun onCategoryChange(category: ExerciseCategory) =
        _uiState.update { it.copy(category = category) }

    fun onMuscleGroupChange(value: String) =
        _uiState.update { it.copy(muscleGroup = value) }

    fun onDefaultSetsChange(value: String) =
        _uiState.update { it.copy(defaultSets = value, numberErrorRes = null) }

    fun onDefaultRepsChange(value: String) =
        _uiState.update { it.copy(defaultReps = value, numberErrorRes = null) }

    fun onDefaultDurationChange(value: String) =
        _uiState.update { it.copy(defaultDurationSec = value, numberErrorRes = null) }

    /** 消费一次 Snackbar。 */
    fun onConsumeSnackbar() = _uiState.update { it.copy(snackbarRes = null) }

    /** 加载失败后重试。 */
    fun onRetry() {
        _uiState.update {
            it.copy(isLoading = true, errorRes = null, saved = false)
        }
        load()
    }

    /**
     * 校验并保存。
     *
     * 校验顺序：名称非空 → 数字合法 → 名称不重复（`nameExists` 排除自身）。
     * 内置动作仅允许改默认值，名称/分类保持不变。
     */
    fun onSave() {
        val state = _uiState.value
        val trimmedName = state.name.trim()
        val sets = state.defaultSets.trim().toIntOrNull()
        val reps = state.defaultReps.trim().toIntOrNull()
        val durationInput = state.defaultDurationSec.trim()
        val duration = durationInput.toIntOrNull()

        if (trimmedName.isEmpty()) {
            _uiState.update { it.copy(nameErrorRes = R.string.error_name_empty) }
            return
        }
        val durationValid = durationInput.isEmpty() || duration != null
        if (sets == null || reps == null || !durationValid) {
            _uiState.update { it.copy(numberErrorRes = R.string.error_invalid_number) }
            return
        }

        viewModelScope.launch {
            try {
                val existing = if (exerciseId != 0L) exerciseRepository.getById(exerciseId) else null
                val builtIn = existing?.isBuiltIn ?: false

                if (!builtIn && exerciseRepository.nameExists(trimmedName, exerciseId)) {
                    _uiState.update { it.copy(nameErrorRes = R.string.error_name_exists) }
                    return@launch
                }

                val nowMillis = clock.now().toEpochMilliseconds()
                val exercise = Exercise(
                    id = existing?.id ?: 0L,
                    name = if (builtIn) existing?.name.orEmpty() else trimmedName,
                    category = if (builtIn) (existing?.category ?: state.category) else state.category,
                    muscleGroup = state.muscleGroup.trim().takeIf { it.isNotEmpty() },
                    isBuiltIn = builtIn,
                    isActive = existing?.isActive ?: true,
                    defaultSets = sets,
                    defaultReps = reps,
                    defaultDurationSec = duration,
                    sortOrder = existing?.sortOrder ?: 0,
                    timesUsed = existing?.timesUsed ?: 0,
                    createdAt = existing?.createdAt?.takeIf { it > 0L } ?: nowMillis,
                )
                exerciseRepository.upsert(exercise)
                _uiState.update {
                    it.copy(snackbarRes = R.string.msg_saved, saved = true)
                }
            } catch (throwable: Throwable) {
                _uiState.update { it.copy(snackbarRes = R.string.error_save_failed) }
            }
        }
    }
}

/** 用已读取的动作填充表单（编辑态）。 */
private fun AddEditExerciseUiState.fromExercise(exercise: Exercise): AddEditExerciseUiState = copy(
    isEditing = true,
    isBuiltIn = exercise.isBuiltIn,
    name = exercise.name,
    category = exercise.category,
    muscleGroup = exercise.muscleGroup.orEmpty(),
    defaultSets = (exercise.defaultSets ?: DEFAULT_SETS).toString(),
    defaultReps = (exercise.defaultReps ?: DEFAULT_REPS).toString(),
    defaultDurationSec = exercise.defaultDurationSec?.toString().orEmpty(),
)

private const val DEFAULT_SETS = 3
private const val DEFAULT_REPS = 12
