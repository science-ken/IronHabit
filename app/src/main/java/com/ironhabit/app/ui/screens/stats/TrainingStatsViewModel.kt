package com.ironhabit.app.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.usecase.GetHeatmapUseCase
import com.ironhabit.app.domain.usecase.GetStatsUseCase
import com.ironhabit.app.domain.usecase.StatsBundle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * 「训练统计」页 ViewModel：区间可选的柱状 / 占比 / 热力图。
 *
 * 这三块本来就在「我的」页首屏，搬过来**不涉及新的数据查询** —— 走的还是
 * [GetStatsUseCase] 与 [GetHeatmapUseCase]，只是把写死的 30 天换成了 [RANGE_DAYS] 里的一个。
 *
 * 与「我的」页同一套触发器做法：`StatsDao` 没有 Flow，所以用
 * [CheckInRepository.observeActiveDaysSince] 当"打过卡了"的信号来重算（架构 §4.2）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrainingStatsViewModel @Inject constructor(
    private val getStats: GetStatsUseCase,
    private val getHeatmap: GetHeatmapUseCase,
    private val checkInRepository: CheckInRepository,
) : ViewModel() {

    /** 当前区间（7 / 30 / 90 天）。切它即整页重算。 */
    private val daysState = MutableStateFlow(DEFAULT_DAYS)

    /** 数据流重订阅触发器（失败重试）。 */
    private val retryTrigger = MutableStateFlow(0L)

    val uiState: StateFlow<TrainingStatsUiState> = retryTrigger
        .flatMapLatest { daysState.flatMapLatest { days -> loadFor(days) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = TrainingStatsUiState(),
        )

    /** 选区间：柱状 / 占比 / 热力图三块一起换，不允许两张图说两段时间。 */
    fun onRangeSelected(days: Int) {
        daysState.update { days }
    }

    /** 加载失败后重试（重新订阅数据源）。 */
    fun onRetry() {
        retryTrigger.update { it + 1L }
    }

    private fun loadFor(days: Int) =
        checkInRepository.observeActiveDaysSince(TRIGGER_SINCE_EPOCH_DAY)
            .map {
                // 一次取回、两处取值：`getStats` 内部要读两趟库，调两遍就是四趟，
                // 而且两趟之间打过卡会让柱状和饼图各自停在不同的快照上。
                val bundle: StatsBundle = getStats(days)
                TrainingStatsUiState(
                    isLoading = false,
                    days = days,
                    trend = bundle.trend,
                    categoryShare = bundle.categoryShare,
                    heatmap = getHeatmap(days),
                )
            }
            // 每次（重）订阅、以及每次切区间都先发一帧「加载中」：
            // 否则与已缓存值完全相同的失败帧会被 StateFlow 去重丢掉，界面永远卡在重试前。
            .onStart { emit(TrainingStatsUiState(days = days)) }
            .catch { emit(TrainingStatsUiState(isLoading = false, days = days, errorRes = R.string.error_load_failed)) }

    companion object {
        /** 默认区间：与改版前「我的」页那两张图的口径一致，老用户看到的数不会莫名变。 */
        const val DEFAULT_DAYS: Int = 30

        /** 可选区间（chip 顺序即此顺序）。 */
        val RANGE_DAYS: List<Int> = listOf(7, 30, 90)

        private const val TRIGGER_SINCE_EPOCH_DAY: Long = 0L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
