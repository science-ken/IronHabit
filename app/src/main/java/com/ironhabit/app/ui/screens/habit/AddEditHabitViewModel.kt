package com.ironhabit.app.ui.screens.habit

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitFrequency
import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.ReminderScheduler
import com.ironhabit.app.domain.repository.ReminderType
import com.ironhabit.app.ui.navigation.Destinations
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * 「新增 / 编辑习惯」UI 状态（不可变）。
 *
 * @property isLoading 首帧加载中
 * @property isEditing `true` = 编辑已有习惯
 * @property name 习惯名
 * @property emoji 图标 emoji
 * @property colorHex 主题色 `#RRGGBB`
 * @property frequency 频率（每天 / 每周）
 * @property weeklyDaysMask 星期掩码（bit0 = 周一 … bit6 = 周日）
 * @property reminderEnabled 是否提醒
 * @property reminderHour 提醒小时（`0..23`）
 * @property reminderMinute 提醒分钟（`0..59`）
 * @property note 备注（v2）
 * @property targetValue 目标数值文本（v2）。空 = 纯勾选型习惯；非空 = 计量型（如 8 杯水）
 * @property targetUnit 目标单位文案（v2，如 杯 / 分钟 / 步）
 * @property nameErrorRes 名称校验错误资源 id
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性 Snackbar 资源 id
 * @property saved 保存成功标志
 */
data class AddEditHabitUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val name: String = "",
    val emoji: String = DEFAULT_EMOJI,
    val colorHex: String = DEFAULT_COLOR_HEX,
    val frequency: HabitFrequency = HabitFrequency.DAILY,
    val weeklyDaysMask: Int = Habit.WEEKLY_DAYS_ALL,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = DEFAULT_REMINDER_HOUR,
    val reminderMinute: Int = DEFAULT_REMINDER_MINUTE,
    val note: String = "",
    val targetValue: String = "",
    val targetUnit: String = "",
    @StringRes val nameErrorRes: Int? = null,
    @StringRes val errorRes: Int? = null,
    @StringRes val snackbarRes: Int? = null,
    val saved: Boolean = false,
)

private const val DEFAULT_EMOJI = "\u2705"
private const val DEFAULT_COLOR_HEX = "#2196F3"
private const val DEFAULT_REMINDER_HOUR = 20
private const val DEFAULT_REMINDER_MINUTE = 0

/**
 * 「新增 / 编辑习惯」ViewModel。
 *
 * 保存后按提醒开关联动 [ReminderScheduler]：开启 → 排 HABIT 提醒；关闭 → 取消 HABIT 提醒。
 */
@HiltViewModel
class AddEditHabitViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val habitRepository: HabitRepository,
    private val reminderScheduler: ReminderScheduler,
    private val clock: Clock,
) : ViewModel() {

    /** 路由参数：`0` 表示新增。 */
    private val habitId: Long =
        savedStateHandle.get<Long>(Destinations.HABIT_ARG_ID) ?: 0L

    private val _uiState = MutableStateFlow(AddEditHabitUiState(isEditing = habitId != 0L))
    val uiState: StateFlow<AddEditHabitUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /**
     * 编辑态：读取旧习惯**完整回填**表单；新增态直接进入可编辑。
     *
     * ⚠️ 必须回填 `note` / `targetValue` / `targetUnit`：否则 `onSave()` 会用空表单值
     * 覆盖已保存的目标值与备注（「只改 emoji 保存 → 目标值/备注被静默清空」的数据丢失 bug）。
     * `target_value` 是 `REAL?`，空值回填为空字符串（而非 `0.0`），整数不带小数点（`8.0 → "8"`）。
     */
    private fun load() {
        if (habitId == 0L) {
            _uiState.update { it.copy(isLoading = false, isEditing = false) }
            return
        }
        viewModelScope.launch {
            runCatching {
                habitRepository.observeActiveHabits().first().firstOrNull { it.id == habitId }
            }
                .onSuccess { habit ->
                    if (habit == null) {
                        _uiState.update {
                            it.copy(isLoading = false, errorRes = R.string.error_load_failed)
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isEditing = true,
                                name = habit.name,
                                emoji = habit.emoji,
                                colorHex = habit.colorHex,
                                frequency = habit.frequency,
                                weeklyDaysMask = habit.weeklyDaysMask,
                                reminderEnabled = habit.reminderEnabled,
                                reminderHour = habit.reminderHour ?: DEFAULT_REMINDER_HOUR,
                                reminderMinute = habit.reminderMinute ?: DEFAULT_REMINDER_MINUTE,
                                note = habit.note.orEmpty(),
                                // 空值还原为空字符串（而非 "0.0"）；整数去掉小数点，避免回显成 "8.0"。
                                targetValue = habit.targetValue
                                    ?.let { value ->
                                        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
                                    }
                                    .orEmpty(),
                                targetUnit = habit.targetUnit.orEmpty(),
                            )
                        }
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

    fun onEmojiChange(value: String) = _uiState.update { it.copy(emoji = value) }

    fun onColorChange(hex: String) = _uiState.update { it.copy(colorHex = hex) }

    fun onFrequencyChange(frequency: HabitFrequency) =
        _uiState.update { it.copy(frequency = frequency) }

    /** 切换星期掩码中的某一位（`index`：`0` = 周一 … `6` = 周日）。 */
    fun onToggleWeekday(index: Int) = _uiState.update { state ->
        val bit = 1 shl index
        state.copy(weeklyDaysMask = state.weeklyDaysMask xor bit)
    }

    fun onReminderEnabledChange(enabled: Boolean) =
        _uiState.update { it.copy(reminderEnabled = enabled) }

    fun onReminderTimeChange(hour: Int, minute: Int) =
        _uiState.update { it.copy(reminderHour = hour, reminderMinute = minute) }

    fun onNoteChange(value: String) = _uiState.update { it.copy(note = value) }

    fun onTargetValueChange(value: String) = _uiState.update { it.copy(targetValue = value) }

    fun onTargetUnitChange(value: String) = _uiState.update { it.copy(targetUnit = value) }

    fun onConsumeSnackbar() = _uiState.update { it.copy(snackbarRes = null) }

    fun onRetry() {
        _uiState.update { it.copy(isLoading = true, errorRes = null, saved = false) }
        load()
    }

    /** 校验并保存习惯，并按提醒开关联动排期 / 取消。 */
    fun onSave() {
        val state = _uiState.value
        val trimmedName = state.name.trim()
        if (trimmedName.isEmpty()) {
            _uiState.update { it.copy(nameErrorRes = R.string.error_name_empty) }
            return
        }

        val mask = if (state.frequency == HabitFrequency.WEEKLY) {
            state.weeklyDaysMask.takeIf { it != 0 } ?: Habit.WEEKLY_DAYS_ALL
        } else {
            Habit.WEEKLY_DAYS_ALL
        }

        val targetInput = state.targetValue.trim()
        val targetValue = targetInput.toDoubleOrNull()
        if (targetInput.isNotEmpty() && targetValue == null) {
            _uiState.update { it.copy(snackbarRes = R.string.error_invalid_number) }
            return
        }
        val targetUnit = state.targetUnit.trim().takeIf { it.isNotEmpty() }

        viewModelScope.launch {
            try {
                val existing = if (habitId != 0L) {
                    habitRepository.observeActiveHabits().first().firstOrNull { it.id == habitId }
                } else {
                    null
                }
                val nowMillis = clock.now().toEpochMilliseconds()
                val habit = Habit(
                    id = existing?.id ?: 0L,
                    name = trimmedName,
                    emoji = state.emoji.trim().ifEmpty { DEFAULT_EMOJI },
                    colorHex = state.colorHex,
                    frequency = state.frequency,
                    weeklyDaysMask = mask,
                    reminderEnabled = state.reminderEnabled,
                    reminderHour = state.reminderHour.takeIf { state.reminderEnabled },
                    reminderMinute = state.reminderMinute.takeIf { state.reminderEnabled },
                    note = state.note.trim().takeIf { it.isNotEmpty() },
                    targetValue = targetValue,
                    // 纯勾选型习惯（无目标值）不保留单位，避免出现「无目标却有单位」的脏数据。
                    targetUnit = if (targetValue != null) targetUnit else null,
                    isActive = existing?.isActive ?: true,
                    sortOrder = existing?.sortOrder ?: 0,
                    createdAt = existing?.createdAt?.takeIf { it > 0L } ?: nowMillis,
                )
                habitRepository.upsertHabit(habit)

                if (state.reminderEnabled) {
                    reminderScheduler.schedule(
                        ReminderType.HABIT,
                        state.reminderHour,
                        state.reminderMinute,
                    )
                } else {
                    reminderScheduler.cancel(ReminderType.HABIT)
                }

                _uiState.update { it.copy(snackbarRes = R.string.msg_saved, saved = true) }
            } catch (throwable: Throwable) {
                _uiState.update { it.copy(snackbarRes = R.string.error_save_failed) }
            }
        }
    }
}
