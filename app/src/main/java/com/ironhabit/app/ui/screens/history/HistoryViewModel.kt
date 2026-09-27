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
import com.ironhabit.app.domain.util.TodayClock
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
 *
 * 读取失败时页面给的是**页内重试**（[onRetry] 重订阅整条聚合流），
 * 形状照 [com.ironhabit.app.ui.screens.profile.ProfileViewModel] —— 本工程里
 * "错误态 + 页内重试"只有一种写法，新页面别再发明第二种。
 *
 * ⚠️ 时间窗口跟着 [TodayClock.epochDay] 走：以前 `observeBetween(起点, 今天)` 的两个参数
 * 是在**订阅那一刻**算好的，App 常驻开着跨过 00:00，热力图与完成率（每次发射重读时钟）
 * 都有今天，只有下面那份列表没有 —— 一屏自相矛盾（审查报告 P2-6）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val checkInRepository: CheckInRepository,
    private val exerciseRepository: ExerciseRepository,
    private val getHeatmap: GetHeatmapUseCase,
    private val statsRepository: StatsRepository,
    private val todayClock: TodayClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    /** 数据流重订阅触发器（失败重试）：自增即让下面的聚合流整体重订阅一次。 */
    private val retryTrigger = MutableStateFlow(0L)

    /** 换天与重试走同一条通道：任一个变了就整条重订阅，窗口参数在下面统一取。 */
    private val resubscribe: Flow<Long> =
        combine(retryTrigger, todayClock.epochDay) { _, today: Long -> today }

    private val dataState: StateFlow<HistoryUiState> = resubscribe
        .flatMapLatest { today: Long ->
            val windowStart: Long = today - (HISTORY_DAYS - 1L)
            combine(
                checkInRepository.observeBetween(windowStart, today),
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
                        completionRate = statsRepository.completionRate(windowStart, today),
                    )
                }
                // 每次（重）订阅都先发一帧「加载中」：否则重试再次失败时，与已缓存的错误态
                // 完全相同的值会被 StateFlow 去重丢掉，界面会永远卡在重试前的状态。
                .onStart { emit(HistoryUiState()) }
                .catch {
                    emit(HistoryUiState(isLoading = false, errorRes = R.string.error_load_failed))
                }
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

    /** 加载失败后重试（重新订阅数据源）。 */
    fun onRetry() {
        _uiState.update { state -> state.copy(isLoading = true, errorRes = null) }
        retryTrigger.update { it + 1L }
    }

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
