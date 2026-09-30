package com.ironhabit.app.ui.screens.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.AppSettings
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.ScheduleReminderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 「设置」UI 状态（不可变）。
 *
 * @property isLoading 首帧加载中
 * @property themeMode 主题模式
 * @property unitSystem 单位制
 * @property reminderEnabled 每日提醒开关
 * @property reminderHour 提醒小时
 * @property reminderMinute 提醒分钟
 * @property profile 用户档案（「我的档案」区块，存 DataStore）
 * @property currentWeightKg 当前体重（只读，来自 `body_metrics` 最新 WEIGHT 值；无记录为 `null`）
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
    val profile: UserProfile = UserProfile(),
    val currentWeightKg: Float? = null,
    @StringRes val errorRes: Int? = null,
    @StringRes val snackbarRes: Int? = null,
    val snackbarArgs: List<String> = emptyList(),
)

private const val DEFAULT_HOUR = 20
private const val DEFAULT_MINUTE = 0

/**
 * 「设置」ViewModel：读取 [SettingsRepository] 设置流 + 用户档案流，并读取
 * [BodyMetricRepository] 的最新体重（**只读展示**，体重唯一真源是 `body_metrics`，不入档案）。
 *
 * 主题/单位/提醒/档案改动均**即时落 DataStore**（无独立保存按钮）。
 * 提醒开关或时间改动后调用 [ScheduleReminderUseCase]（内部自读最新设置决定排期/取消）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val bodyMetricRepository: BodyMetricRepository,
    private val scheduleReminder: ScheduleReminderUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** 数据流重订阅触发器（失败重试）：自增即让下面的聚合流整体重订阅一次。 */
    private val retryTrigger = MutableStateFlow(0L)

    /** 最新体重（只读）；无记录为 `null`。 */
    private val latestWeightKg: Flow<Float?> =
        bodyMetricRepository.observeByType(BodyMetricType.WEIGHT)
            .map { metrics -> metrics.maxByOrNull { it.dateEpochDay }?.value }

    private val dataState: StateFlow<SettingsUiState> = retryTrigger
        .flatMapLatest {
            combine(
                settingsRepository.settings(),
                settingsRepository.profile(),
                latestWeightKg,
            ) { settings: AppSettings, profile: UserProfile, weightKg: Float? ->
                settings.toUiState().copy(
                    profile = profile,
                    currentWeightKg = weightKg,
                )
            }
                // 每次（重）订阅都先发一帧「加载中」：否则重试再次失败时，与已缓存的错误态
                // 完全相同的值会被 StateFlow 去重丢掉，界面会永远卡在重试前的状态。
                .onStart { emit(SettingsUiState()) }
                .catch {
                    emit(SettingsUiState(isLoading = false, errorRes = R.string.error_load_failed))
                }
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
                        // 本地一次性状态不被数据流覆盖。
                        snackbarRes = local.snackbarRes,
                        snackbarArgs = local.snackbarArgs,
                        // 这一帧没报错 = 数据是好的：把旧错误续下去会让整页一直停在错误分支，
                        // 而那正是"重试"要清掉的东西（见 [onRetry]）。
                        errorRes = data.errorRes,
                    )
                }
            }
        }
    }

    /** 加载失败后重试（重新订阅数据源）。 */
    fun onRetry() {
        _uiState.update { state -> state.copy(isLoading = true, errorRes = null) }
        retryTrigger.update { it + 1L }
    }

    fun onThemeChange(mode: ThemeMode) {
        persist { settingsRepository.setTheme(mode) }
    }

    fun onUnitChange(system: UnitSystem) {
        persist { settingsRepository.setUnit(system) }
    }

    // ---------------- 我的档案（即时落盘，无保存按钮）----------------

    /** 选择性别。 */
    fun onProfileGenderChange(gender: Gender?) {
        persist { settingsRepository.setProfileGender(gender) }
    }

    /** 修改年龄（`null` = 清空）。 */
    fun onProfileAgeChange(age: Int?) {
        persist { settingsRepository.setProfileAge(age) }
    }

    /** 修改身高（`null` = 清空）。 */
    fun onProfileHeightChange(heightCm: Int?) {
        persist { settingsRepository.setProfileHeightCm(heightCm) }
    }

    /** 修改体脂率（`null` = 清空）。 */
    fun onProfileBodyFatChange(bodyFatPct: Float?) {
        persist { settingsRepository.setProfileBodyFatPct(bodyFatPct) }
    }

    /** 选择健身目标。 */
    fun onProfileGoalChange(goal: Goal) {
        persist { settingsRepository.setProfileGoal(goal) }
    }

    /** 修改目标体重（`null` = 清空）。 */
    fun onProfileGoalWeightChange(goalWeightKg: Float?) {
        persist { settingsRepository.setProfileGoalWeightKg(goalWeightKg) }
    }

    /** 勾选/取消某项可用器械（读最新集合再翻转，避免连点丢改动）。 */
    fun onProfileEquipmentToggle(item: Equipment) {
        persist {
            val current = settingsRepository.profile().first().equipment
            settingsRepository.setProfileEquipment(toggle(current, item))
        }
    }

    /** 勾选/取消某个伤病部位。 */
    fun onProfileInjuryAreaToggle(item: InjuryArea) {
        persist {
            val current = settingsRepository.profile().first().injuryAreas
            settingsRepository.setProfileInjuryAreas(toggle(current, item))
        }
    }

    /** 修改伤病备注（空白 = 清空）。 */
    fun onProfileInjuryNoteChange(note: String?) {
        persist { settingsRepository.setProfileInjuryNote(note) }
    }

    /** 选择每周训练天数（P1：`3–6`，越界由仓库钳制）。 */
    fun onProfileTrainingDaysChange(days: Int) {
        persist { settingsRepository.setProfileTrainingDaysPerWeek(days) }
    }

    /** 勾选/取消某项饮食忌口。 */
    fun onProfileDietAvoidToggle(item: DietRestriction) {
        persist {
            val current = settingsRepository.profile().first().dietaryAvoid
            settingsRepository.setProfileDietaryAvoid(toggle(current, item))
        }
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
            } catch (cancellation: CancellationException) {
                throw cancellation
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
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _uiState.update { it.copy(snackbarRes = R.string.error_generic) }
            }
        }
    }

    fun onConsumeSnackbar() {
        _uiState.update { it.copy(snackbarRes = null, snackbarArgs = emptyList()) }
    }

    // ---------------- 内部 ----------------

    /**
     * 写操作串行闸（V2 报告 V2-P3-4）：器械 / 伤病 / 忌口三处 toggle 都是
     * "读 profile 最新集合 → 翻转 → 写回"的读改写，两次快速连点如果并行跑，
     * 各自读到的都是翻转前的旧集合，后写覆盖前写 → 丢一次勾选。
     * 全部写路径收进同一把 [persistMutex] 后，读改写天然排队，每次读到的都是上一次的落库结果。
     */
    private val persistMutex = Mutex()

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                persistMutex.withLock { block() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _uiState.update { it.copy(snackbarRes = R.string.error_generic) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** 集合翻转：已含则移除，未含则加入。 */
        fun <T> toggle(set: Set<T>, item: T): Set<T> =
            if (item in set) set - item else set + item
    }
}

/**
 * `HH:mm`（纯数字，无硬编码中文）。
 *
 * 必须钉 `Locale.ROOT`：`String.format` 的默认重载按**设备语言**渲染数字，
 * 在阿拉伯语一类 locale 下 `07:30` 会变成当地数字（审查报告 P3-33），
 * 而这一串同时出现在设置页正文与保存后的 Snackbar 里。
 * 时间读数不是本地化文案 —— 全 App 它只该有一种写法。
 */
private fun formatTime(hour: Int, minute: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", hour, minute)

/** [AppSettings] → [SettingsUiState]（档案 / 体重 / 瞬态字段由 ViewModel 维护）。 */
private fun AppSettings.toUiState(): SettingsUiState = SettingsUiState(
    isLoading = false,
    themeMode = themeMode,
    unitSystem = unitSystem,
    reminderEnabled = reminderEnabled,
    reminderHour = reminderHour,
    reminderMinute = reminderMinute,
)
