package com.ironhabit.app.ui.screens.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.AppSettings
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.ScheduleReminderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「设置」UI 状态（不可变）。
 *
 * @property isLoading 首帧加载中
 * @property themeMode 主题模式
 * @property unitSystem 单位制
 * @property reminderEnabled 每日提醒开关
 * @property reminderHour 提醒小时
 * @property reminderMinute 提醒分钟
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性 Snackbar 资源 id（提醒设置结果）
 * @property snackbarArgs Snackbar 格式化参数（提醒时间）
 */
data class SettingsUiState(
    val isLoading: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = DEFAULT_HOUR,
    val reminderMinute: Int = DEFAULT_MINUTE,
    @StringRes val errorRes: Int? = null,
    @StringRes val snackbarRes: Int? = null,
    val snackbarArgs: List<String> = emptyList(),
)

private const val DEFAULT_HOUR = 20
private const val DEFAULT_MINUTE = 0

/**
 * 「设置」ViewModel：读取 [SettingsRepository] 设置流，主题/单位/提醒改动落 DataStore。
 *
 * 提醒开关或时间改动后调用 [ScheduleReminderUseCase]（内部自读最新设置决定排期/取消）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val scheduleReminder: ScheduleReminderUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val dataState: StateFlow<SettingsUiState> =
        settingsRepository.settings()
            .map { settings: AppSettings -> settings.toUiState() }
            .catch {
                emit(SettingsUiState(isLoading = false, errorRes = R.string.error_load_failed))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = SettingsUiState(),
            )

    init {
        viewModelScope.launch {
            dataState.collect { data ->
                _uiState.update { local ->
                    data.copy(
                        snackbarRes = local.snackbarRes,
                        snackbarArgs = local.snackbarArgs,
                        errorRes = data.errorRes ?: local.errorRes,
                    )
                }
            }
        }
    }

    fun onThemeChange(mode: ThemeMode) {
        persist { settingsRepository.setTheme(mode) }
    }

    fun onUnitChange(system: UnitSystem) {
        persist { settingsRepository.setUnit(system) }
    }

    /** 开关每日提醒，并重排/取消提醒。 */
    fun onReminderEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setReminderEnabled(enabled)
                scheduleReminder()
                if (enabled) {
                    val settings = settingsRepository.settings().first()
                    _uiState.update {
                        it.copy(
                            snackbarRes = R.string.msg_reminder_set,
                            snackbarArgs = listOf(formatTime(settings.reminderHour, settings.reminderMinute)),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(snackbarRes = R.string.msg_reminder_off, snackbarArgs = emptyList())
                    }
                }
            } catch (throwable: Throwable) {
                _uiState.update { it.copy(snackbarRes = R.string.error_generic) }
            }
        }
    }

    /** 修改提醒时间，并重排提醒。 */
    fun onReminderTimeChange(hour: Int, minute: Int) {
        viewModelScope.launch {
            try {
                settingsRepository.setReminderTime(hour, minute)
                scheduleReminder()
                _uiState.update {
                    it.copy(
                        snackbarRes = R.string.msg_reminder_set,
                        snackbarArgs = listOf(formatTime(hour, minute)),
                    )
                }
            } catch (throwable: Throwable) {
                _uiState.update { it.copy(snackbarRes = R.string.error_generic) }
            }
        }
    }

    fun onConsumeSnackbar() {
        _uiState.update { it.copy(snackbarRes = null, snackbarArgs = emptyList()) }
    }

    // ---------------- 内部 ----------------

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (throwable: Throwable) {
                _uiState.update { it.copy(snackbarRes = R.string.error_generic) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** `HH:mm`（纯数字，无硬编码中文）。 */
private fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

/** [AppSettings] → [SettingsUiState]（瞬态字段由 ViewModel 维护）。 */
private fun AppSettings.toUiState(): SettingsUiState = SettingsUiState(
    isLoading = false,
    themeMode = themeMode,
    unitSystem = unitSystem,
    reminderEnabled = reminderEnabled,
    reminderHour = reminderHour,
    reminderMinute = reminderMinute,
)
