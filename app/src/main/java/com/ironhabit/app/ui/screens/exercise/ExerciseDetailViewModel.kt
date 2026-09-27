package com.ironhabit.app.ui.screens.exercise

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.ui.navigation.Destinations
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「动作详情」UI 状态（不可变）。
 *
 * @property isLoading 首帧加载中
 * @property exerciseId 当前动作 id（供「编辑」入口复用）
 * @property exerciseName 动作名
 * @property category 分类
 * @property muscleGroup 目标肌群（可空）
 * @property timesUsed 累计打卡次数
 * @property history 该动作的打卡历史（按日期倒序）
 * @property errorRes 页面级错误资源 id
 */
data class ExerciseDetailUiState(
    val isLoading: Boolean = true,
    val exerciseId: Long = 0L,
    val exerciseName: String = "",
    val category: ExerciseCategory = ExerciseCategory.CUSTOM,
    val muscleGroup: String? = null,
    val timesUsed: Int = 0,
    val history: List<CheckIn> = emptyList(),
    @StringRes val errorRes: Int? = null,
)

/**
 * 「动作详情」ViewModel：动作信息（一次性读取）+ 打卡历史（Room 响应式 [CheckInRepository.observeByExercise]）。
 *
 * 历史每次变化都会重新读取动作信息，从而保证「打卡后次数 +1」等改动自动刷新。
 */
@HiltViewModel
class ExerciseDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val checkInRepository: CheckInRepository,
) : ViewModel() {

    /** 路由参数：动作 id（path 参数，必填）。 */
    private val exerciseId: Long =
        savedStateHandle.get<Long>(Destinations.EXERCISE_DETAIL_ARG) ?: 0L

    private val _uiState = MutableStateFlow(ExerciseDetailUiState(exerciseId = exerciseId))
    val uiState: StateFlow<ExerciseDetailUiState> = _uiState.asStateFlow()

    /** 数据流重订阅触发器（失败重试）：自增即让下面的流整体重订阅一次。 */
    private val retryTrigger = MutableStateFlow(0L)

    private val dataState: StateFlow<ExerciseDetailUiState> = retryTrigger
        .flatMapLatest {
            checkInRepository.observeByExercise(exerciseId)
                .map { checkIns ->
                    val exercise = exerciseRepository.getById(exerciseId)
                    if (exercise == null) {
                        ExerciseDetailUiState(
                            isLoading = false,
                            exerciseId = exerciseId,
                            history = checkIns,
                            errorRes = R.string.error_load_failed,
                        )
                    } else {
                        ExerciseDetailUiState(
                            isLoading = false,
                            exerciseId = exercise.id,
                            exerciseName = exercise.name,
                            category = exercise.category,
                            muscleGroup = exercise.primaryMuscleGroup,
                            timesUsed = exercise.timesUsed,
                            history = checkIns,
                        )
                    }
                }
                // 每次（重）订阅都先发一帧「加载中」：否则重试再次失败时，与已缓存的错误态
                // 完全相同的值会被 StateFlow 去重丢掉，界面会永远卡在重试前的状态。
                .onStart { emit(ExerciseDetailUiState(exerciseId = exerciseId)) }
                .catch {
                    emit(
                        ExerciseDetailUiState(
                            isLoading = false,
                            exerciseId = exerciseId,
                            errorRes = R.string.error_load_failed,
                        ),
                    )
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = ExerciseDetailUiState(exerciseId = exerciseId),
        )

    init {
        viewModelScope.launch {
            dataState.collect { data -> _uiState.value = data }
        }
    }

    /** 加载失败后重试（重新订阅数据源）。 */
    fun onRetry() {
        _uiState.update { state -> state.copy(isLoading = true, errorRes = null) }
        retryTrigger.update { it + 1L }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
