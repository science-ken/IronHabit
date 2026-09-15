package com.ironhabit.app.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.GetStatsUseCase
import com.ironhabit.app.domain.usecase.StatsBundle
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「我的」页 ViewModel：近 30 天趋势 + 训练类型占比两图 + 「身体档案」概要（只读）。
 *
 * 以 [CheckInRepository.observeActiveDaysSince]（`0L`）作为**变更触发器**，
 * `map { getStats(30) }` 保证打卡后图表自动刷新（`StatsDao` 无 Flow，故用触发器驱动的做法）；
 * 档案概要来自 [SettingsRepository.profile]（DataStore 流）。
 *
 * `retryTrigger` 让加载失败后可以由页面上的「重试」**重新订阅**整条数据流（[onRetry]）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getStats: GetStatsUseCase,
    private val checkInRepository: CheckInRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /** 数据流重订阅触发器（失败重试）：自增即让下面的聚合流整体重订阅一次。 */
    private val retryTrigger = MutableStateFlow(0L)

    private val dataState: StateFlow<ProfileUiState> = retryTrigger
        .flatMapLatest {
            combine(
                checkInRepository.observeActiveDaysSince(TRIGGER_SINCE_EPOCH_DAY),
                settingsRepository.profile(),
            ) { _: List<Long>, profile: UserProfile -> profile }
                .map { profile: UserProfile ->
                    val bundle: StatsBundle = getStats(TREND_DAYS)
                    ProfileUiState(
                        isLoading = false,
                        trend = bundle.trend,
                        categoryShare = bundle.categoryShare,
                        profile = profile,
                    )
                }
                // 每次（重）订阅都先发一帧「加载中」：否则重试再次失败时，
                // 与已缓存的错误态完全相同的值会被 StateFlow 去重丢掉，界面会永远卡在重试前的状态。
                .onStart { emit(ProfileUiState()) }
                .catch {
                    emit(ProfileUiState(isLoading = false, errorRes = R.string.error_load_failed))
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = ProfileUiState(),
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
        const val TRIGGER_SINCE_EPOCH_DAY: Long = 0L
        const val TREND_DAYS = 30
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
