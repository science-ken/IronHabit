package com.ironhabit.app.ui.screens.history

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.StatsRepository
import com.ironhabit.app.domain.usecase.GetHeatmapUseCase
import com.ironhabit.app.domain.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 单条历史项：动作名 + 组×次 + 重量。
 *
 * @property exerciseName 动作名（无法解析时回落 `#id`）
 * @property completedSets 实际组数
 * @property completedReps 实际每组次数
 * @property weightKg 重量（kg），可空
 */
data class HistoryItem(
    val exerciseName: String,
    val completedSets: Int,
    val completedReps: Int,
    val weightKg: Float?,
)

/**
 * 某天的历史分组。
 *
 * @property epochDay 日期口径
 * @property items 当天的打卡项
 */
data class HistoryDayEntry(
    val epochDay: Long,
    val items: List<HistoryItem>,
)

/**
 * 「打卡历史」UI 状态（不可变）。
 *
 * @property isLoading 首帧加载中
 * @property days 按日期倒序分组的历史
 * @property heatmap 近 180 天热力图数据
 * @property completionRate 区间完成率（`0f..100f`）
 * @property errorRes 页面级错误资源 id
 */
data class HistoryUiState(
    val isLoading: Boolean = true,
    val days: List<HistoryDayEntry> = emptyList(),
    val heatmap: List<HeatmapCell> = emptyList(),
    val completionRate: Float = 0f,
    @StringRes val errorRes: Int? = null,
)

/**
 * 「打卡历史」ViewModel（P0-9）。
 *
 * 以 [CheckInRepository.observeBetween]（近 180 天）+ [ExerciseRepository.observeActive] 组合出分组视图；
 * 热力图与完成率在每次数据变化时重算，保证打卡后自动刷新。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val checkInRepository: CheckInRepository,
    private val exerciseRepository: ExerciseRepository,
    private val getHeatmap: GetHeatmapUseCase,
    private val statsRepository: StatsRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val dataState: StateFlow<HistoryUiState> =
        combine(
            checkInRepository.observeBetween(rangeStartEpochDay(), todayEpochDay()),
            exerciseRepository.observeActive(),
        ) { checkIns, exercises -> checkIns to exercises }
            .map { (checkIns, exercises) ->
                val nameById: Map<Long, String> = exercises.associate { it.id to it.name }
                val days: List<HistoryDayEntry> = checkIns
                    .groupBy { it.dateEpochDay }
                    .map { (epochDay, items) ->
                        HistoryDayEntry(
                            epochDay = epochDay,
                            items = items.map { checkIn -> checkIn.toHistoryItem(nameById) },
                        )
                    }
                    .sortedByDescending { it.epochDay }

                HistoryUiState(
                    isLoading = false,
                    days = days,
                    heatmap = getHeatmap(HEATMAP_DAYS),
                    completionRate = statsRepository.completionRate(
                        rangeStartEpochDay(),
                        todayEpochDay(),
                    ),
                )
            }
            .catch {
                emit(HistoryUiState(isLoading = false, errorRes = R.string.error_load_failed))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = HistoryUiState(),
            )

    init {
        viewModelScope.launch {
            dataState.collect { data -> _uiState.value = data }
        }
    }

    private fun todayEpochDay(): Long = DateUtils.todayEpochDay(clock, timeZone)

    private fun rangeStartEpochDay(): Long = todayEpochDay() - (HISTORY_DAYS - 1L)

    private companion object {
        const val HISTORY_DAYS = 180L
        const val HEATMAP_DAYS = 180
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** 打卡记录 → 历史项（动作名查表，未命中回落 `#id`）。 */
private fun CheckIn.toHistoryItem(nameById: Map<Long, String>): HistoryItem = HistoryItem(
    exerciseName = nameById[exerciseId] ?: "#$exerciseId",
    completedSets = completedSets,
    completedReps = completedReps,
    weightKg = weightKg,
)
