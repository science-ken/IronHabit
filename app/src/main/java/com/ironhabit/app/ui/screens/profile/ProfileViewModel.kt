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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「我的」页 ViewModel：近 30 天趋势 + 训练类型占比两图 + 「身体档案」概要（只读）。
 *
 * 以 [CheckInRepository.observeActiveDaysSince]（`0L`）作为**变更触发器**，
 * `map { getStats(30) }` 保证打卡后图表自动刷新（`StatsDao` 无 Flow，故用触发器驱动的做法）；
 * 档案概要来自 [SettingsRepository.profile]（DataStore 流）。
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getStats: GetStatsUseCase,
    private val checkInRepository: CheckInRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val dataState: StateFlow<ProfileUiState> =
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
            .catch {
                emit(ProfileUiState(isLoading = false, errorRes = R.string.error_load_failed))
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

    private companion object {
        const val TRIGGER_SINCE_EPOCH_DAY: Long = 0L
        const val TREND_DAYS = 30
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
