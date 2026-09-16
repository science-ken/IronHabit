package com.ironhabit.app.ui.screens.settings

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.BuildConfig
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.ProfileLimits
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.LocalSnackbarHostState

/**
 * 「设置」页：主题 / 单位 / 我的档案 / 每日提醒（含精确闹钟与通知权限引导）/ 隐私 / 版本 / 备份入口。
 *
 * 精确闹钟引导（架构 §2.6）：API 31+ 且未授予 `SCHEDULE_EXACT_ALARM`、且提醒已开启时，
 * 在提醒开关下方展示引导条（点击跳系统「闹钟与提醒」设置）；用户拒绝则由 `ReminderSchedulerImpl` 自动降级。
 *
 * 「我的档案」区块（v3 增量 · §7.5）：性别/目标用分段/单选 chip，数值用数字输入框，
 * 器械/伤病/忌口用多选 chip；**控件变更即时落盘、无独立保存按钮**；数值越界钳制 + 轻提示；
 * 当前体重**只读**（唯一真源 `body_metrics`）并提供「去记录」跳转。
 *
 * @param onOpenBackup 数据备份入口回调
 * @param onOpenBodyMetrics 身体数据（记录体重）入口回调
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenBodyMetrics: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current
    val context = LocalContext.current

    var notificationGranted by remember { mutableStateOf(isNotificationGranted(context)) }
    var exactAlarmsAllowed by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationGranted = isNotificationGranted(context)
        exactAlarmsAllowed = canScheduleExactAlarms(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationGranted = granted
    }

    val snackbarText: String? = uiState.snackbarRes?.let { res ->
        stringResource(res, *uiState.snackbarArgs.toTypedArray())
    }
    LaunchedEffect(snackbarText) {
        if (snackbarText != null) {
            snackbarHostState.showSnackbar(snackbarText)
            viewModel.onConsumeSnackbar()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.title_settings),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // 普通本地 val：委托属性不支持智能转换，需先取出再在分支内使用。
        val errorRes: Int? = uiState.errorRes
        when {
            uiState.isLoading -> {
                LoadingSkeleton()
                LoadingSkeleton()
            }

            errorRes != null -> {
                EmptyState(text = stringResource(errorRes))
            }

            else -> {
                // ---- 主题 ----
                SectionLabel(text = stringResource(R.string.settings_theme))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = uiState.themeMode == mode,
                            onClick = { viewModel.onThemeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ThemeMode.entries.size,
                            ),
                            label = { Text(text = stringResource(themeLabelRes(mode))) },
                        )
                    }
                }

                // ---- 单位制 ----
                SectionLabel(text = stringResource(R.string.settings_unit))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    UnitSystem.entries.forEachIndexed { index, system ->
                        SegmentedButton(
                            selected = uiState.unitSystem == system,
                            onClick = { viewModel.onUnitChange(system) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = UnitSystem.entries.size,
                            ),
                            label = { Text(text = stringResource(unitLabelRes(system))) },
                        )
                    }
                }

                // ---- 我的档案 ----
                HorizontalDivider()
                ProfileSection(
                    uiState = uiState,
                    viewModel = viewModel,
                    onOpenBodyMetrics = onOpenBodyMetrics,
                )
                HorizontalDivider()

                // ---- AI 设置（联网增强 · 默认关）----
                AiSettingsSection(
                    uiState = uiState,
                    viewModel = viewModel,
                )
                HorizontalDivider()

                // ---- 每日提醒 ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_reminder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Switch(
                        checked = uiState.reminderEnabled,
                        onCheckedChange = viewModel::onReminderEnabledChange,
                    )
                }

                if (uiState.reminderEnabled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showTimePicker(
                                    context = context,
                                    hour = uiState.reminderHour,
                                    minute = uiState.reminderMinute,
                                ) { hour, minute -> viewModel.onReminderTimeChange(hour, minute) }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_reminder_time),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = formatTime(uiState.reminderHour, uiState.reminderMinute),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.settings_reminder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- 精确闹钟引导（API 31+，提醒开启时）----
                if (uiState.reminderEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAlarmsAllowed) {
                    TipCard(
                        text = stringResource(R.string.settings_exact_alarm_tip),
                        onClick = { context.startActivity(exactAlarmSettingsIntent(context)) },
                    )
                }

                // ---- 通知权限引导（API 33+）----
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
                    TipCard(
                        text = stringResource(R.string.settings_notification_permission_tip),
                        onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        actionText = stringResource(R.string.title_settings),
                        onAction = { context.startActivity(notificationSettingsIntent(context)) },
                    )
                }

                HorizontalDivider()

                // ---- 关于（隐私与版本）----
                Text(
                    text = stringResource(R.string.title_about),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // ---- 备份入口 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenBackup)
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.entry_backup),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------- AI 设置（联网增强 · v3 增量二期） ----------------

/**
 * 「AI 设置」区块：联网开关 + DeepSeek API Key + 隐私说明。
 *
 * **诚实原则**：开关默认关；关闭/未填 Key/断网时行为与纯离线版完全一致（自动回落本地规则）。
 * 隐私说明写明"开了发什么给谁"——**宁朴素勿误导**，不许用"智能云服务"之类的模糊措辞。
 */
@Composable
private fun AiSettingsSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    var keyInput by rememberSaveable { mutableStateOf("") }

    SectionLabel(text = stringResource(R.string.settings_section_ai))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.settings_ai_remote_enabled),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = uiState.aiRemoteEnabled,
            onCheckedChange = viewModel::onAiRemoteEnabledChange,
        )
    }
    Text(
        text = stringResource(R.string.settings_ai_remote_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = keyInput,
        onValueChange = { keyInput = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = stringResource(R.string.settings_ai_key_label)) },
        placeholder = { Text(text = stringResource(R.string.settings_ai_key_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(
                onClick = {
                    viewModel.onApiKeySave(keyInput)
                    keyInput = ""
                },
                enabled = keyInput.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.action_save))
            }
        },
        supportingText = {
            Text(
                text = if (uiState.hasApiKey) {
                    stringResource(R.string.settings_ai_key_saved)
                } else {
                    stringResource(R.string.settings_ai_key_hint)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
    if (uiState.hasApiKey) {
        TextButton(onClick = viewModel::onApiKeyClear) {
            Text(text = stringResource(R.string.action_clear_key))
        }
    }

    Text(
        text = stringResource(R.string.settings_ai_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------- 我的档案（v3 增量） ----------------

/**
 * 「我的档案」区块：体征 / 目标 / 训练条件 / 约束 四区。
 *
 * 所有控件**即时落盘**（无保存按钮）；数值越界钳制 + 轻提示；当前体重只读 + 跳转。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenBodyMetrics: () -> Unit,
) {
    val profile = uiState.profile

    SectionLabel(text = stringResource(R.string.entry_profile_edit))

    // ---- 体征 ----
    Text(
        text = stringResource(R.string.section_profile_body),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.label_profile_gender),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        Gender.entries.forEachIndexed { index, gender ->
            SegmentedButton(
                selected = profile.gender == gender,
                onClick = { viewModel.onProfileGenderChange(gender) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = Gender.entries.size),
                label = { Text(text = stringResource(genderLabelRes(gender))) },
            )
        }
    }
    ProfileIntField(
        value = profile.age,
        range = ProfileLimits.AGE,
        rangeErrorRes = R.string.error_profile_age_range,
        label = stringResource(R.string.label_profile_age),
        suffix = stringResource(R.string.suffix_profile_age),
        hint = stringResource(R.string.hint_profile_age),
        onCommit = viewModel::onProfileAgeChange,
    )
    ProfileIntField(
        value = profile.heightCm,
        range = ProfileLimits.HEIGHT_CM,
        rangeErrorRes = R.string.error_profile_height_range,
        label = stringResource(R.string.label_profile_height),
        suffix = stringResource(R.string.suffix_profile_height),
        hint = stringResource(R.string.hint_profile_height),
        onCommit = viewModel::onProfileHeightChange,
    )
    ProfileFloatField(
        value = profile.bodyFatPct,
        range = ProfileLimits.BODY_FAT_PCT,
        rangeErrorRes = R.string.error_profile_body_fat_range,
        label = stringResource(R.string.label_profile_body_fat),
        suffix = stringResource(R.string.suffix_profile_body_fat),
        hint = stringResource(R.string.hint_profile_body_fat),
        onCommit = viewModel::onProfileBodyFatChange,
    )

    // 当前体重：只读（唯一真源 body_metrics），提供「去记录」跳转。
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.label_profile_current_weight),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val weight = uiState.currentWeightKg
            Text(
                text = if (weight != null) {
                    stringResource(R.string.label_weight_kg, weight.toDisplayNumber())
                } else {
                    stringResource(R.string.value_profile_not_recorded)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = onOpenBodyMetrics) {
                Text(text = stringResource(R.string.action_profile_record_weight))
            }
        }
    }

    // ---- 目标 ----
    Text(
        text = stringResource(R.string.section_profile_goal),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.label_profile_goal),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    ChipGroup(
        options = Goal.entries,
        isSelected = { it == profile.goal },
        labelOf = { stringResource(goalLabelRes(it)) },
        onClick = viewModel::onProfileGoalChange,
    )
    ProfileFloatField(
        value = profile.goalWeightKg,
        range = ProfileLimits.GOAL_WEIGHT_KG,
        rangeErrorRes = R.string.error_profile_goal_weight_range,
        label = stringResource(R.string.label_profile_goal_weight),
        suffix = stringResource(R.string.suffix_profile_goal_weight),
        hint = stringResource(R.string.hint_profile_goal_weight),
        onCommit = viewModel::onProfileGoalWeightChange,
    )

    // ---- 训练条件 ----
    Text(
        text = stringResource(R.string.section_profile_training),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.label_profile_equipment),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    ChipGroup(
        options = Equipment.entries,
        isSelected = { it in profile.equipment },
        labelOf = { stringResource(equipmentLabelRes(it)) },
        onClick = viewModel::onProfileEquipmentToggle,
    )

    // P1：每周训练天数（3–6）—— 规则引擎真的会按它排课。
    Text(
        text = stringResource(R.string.label_profile_training_days),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    ChipGroup(
        options = ProfileLimits.TRAINING_DAYS_PER_WEEK.toList(),
        isSelected = { it == profile.trainingDaysPerWeek },
        labelOf = { stringResource(R.string.label_profile_training_days_option, it) },
        onClick = viewModel::onProfileTrainingDaysChange,
    )
    Text(
        text = stringResource(R.string.hint_profile_training_days),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )

    // ---- 约束（可留空）----
    Text(
        text = stringResource(R.string.section_profile_constraints),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.label_profile_injury_area),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    ChipGroup(
        options = InjuryArea.entries,
        isSelected = { it in profile.injuryAreas },
        labelOf = { stringResource(injuryLabelRes(it)) },
        onClick = viewModel::onProfileInjuryAreaToggle,
    )

    InjuryNoteField(
        value = profile.injuryNote,
        onCommit = viewModel::onProfileInjuryNoteChange,
    )

    Text(
        text = stringResource(R.string.label_profile_diet_avoid),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    ChipGroup(
        options = DietRestriction.entries,
        isSelected = { it in profile.dietaryAvoid },
        labelOf = { stringResource(restrictionLabelRes(it)) },
        onClick = viewModel::onProfileDietAvoidToggle,
    )
}

/** 多选 / 单选 chip 组（横向流式换行）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(
    options: List<T>,
    isSelected: (T) -> Boolean,
    labelOf: @Composable (T) -> String,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = isSelected(option),
                onClick = { onClick(option) },
                label = { Text(text = labelOf(option)) },
            )
        }
    }
}

/**
 * 数字（Int）输入字段：即时落盘、越界钳制、非法/越界轻提示。
 *
 * 本地文本状态优先：用户未编辑时展示 [value]（随数据加载自动回填）；一旦编辑即以用户输入为准，
 * 避免「钳制值回写」打断连续输入。
 */
@Composable
private fun ProfileIntField(
    value: Int?,
    range: IntRange,
    @StringRes rangeErrorRes: Int,
    label: String,
    suffix: String,
    hint: String,
    onCommit: (Int?) -> Unit,
) {
    var edited by remember { mutableStateOf<String?>(null) }
    val text = edited ?: value?.toString().orEmpty()
    val trimmed = text.trim()
    val parsed = trimmed.toIntOrNull()
    val invalid = trimmed.isNotEmpty() && parsed == null
    val outOfRange = parsed != null && parsed !in range

    ProfileNumberField(
        text = text,
        onTextChange = { raw ->
            edited = raw
            val t = raw.trim()
            when {
                t.isEmpty() -> onCommit(null)
                else -> t.toIntOrNull()?.let { onCommit(it.coerceIn(range.first, range.last)) }
            }
        },
        label = label,
        suffix = suffix,
        hint = hint,
        errorText = when {
            invalid -> stringResource(R.string.error_invalid_number)
            outOfRange -> stringResource(rangeErrorRes)
            else -> null
        },
    )
}

/** 数字（Float）输入字段：即时落盘、越界钳制、非法/越界轻提示。 */
@Composable
private fun ProfileFloatField(
    value: Float?,
    range: ClosedFloatingPointRange<Float>,
    @StringRes rangeErrorRes: Int,
    label: String,
    suffix: String,
    hint: String,
    onCommit: (Float?) -> Unit,
) {
    var edited by remember { mutableStateOf<String?>(null) }
    val text = edited ?: value?.toDisplayNumber().orEmpty()
    val trimmed = text.trim()
    val parsed = trimmed.toFloatOrNull()
    val invalid = trimmed.isNotEmpty() && parsed == null
    val outOfRange = parsed != null && parsed !in range

    ProfileNumberField(
        text = text,
        onTextChange = { raw ->
            edited = raw
            val t = raw.trim()
            when {
                t.isEmpty() -> onCommit(null)
                else -> t.toFloatOrNull()?.let {
                    onCommit(it.coerceIn(range.start, range.endInclusive))
                }
            }
        },
        label = label,
        suffix = suffix,
        hint = hint,
        errorText = when {
            invalid -> stringResource(R.string.error_invalid_number)
            outOfRange -> stringResource(rangeErrorRes)
            else -> null
        },
    )
}

/** 数字输入框（数字键盘 + 后缀 + 支持文本错误提示）。 */
@Composable
private fun ProfileNumberField(
    text: String,
    onTextChange: (String) -> Unit,
    label: String,
    suffix: String,
    hint: String,
    errorText: String?,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(text = label) },
        placeholder = { Text(text = hint) },
        suffix = { Text(text = suffix) },
        singleLine = true,
        isError = errorText != null,
        supportingText = if (errorText != null) {
            { Text(text = errorText) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 伤病备注（自由文本，截断到 [ProfileLimits.INJURY_NOTE_MAX_LENGTH] 字，即时落盘）。 */
@Composable
private fun InjuryNoteField(
    value: String?,
    onCommit: (String?) -> Unit,
) {
    var edited by remember { mutableStateOf<String?>(null) }
    val text = edited ?: value.orEmpty()

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val limited = raw.take(ProfileLimits.INJURY_NOTE_MAX_LENGTH)
            edited = limited
            onCommit(limited)
        },
        label = { Text(text = stringResource(R.string.label_profile_injury_note)) },
        placeholder = { Text(text = stringResource(R.string.hint_profile_injury_note)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 浮点展示：整数去小数点尾巴（`20.0f → "20"`），小数原样（`22.5f → "22.5"`）。 */
private fun Float.toDisplayNumber(): String =
    if (this % 1f == 0f) this.toLong().toString() else this.toString()

/** 性别 → 文案资源。 */
@StringRes
private fun genderLabelRes(gender: Gender): Int = when (gender) {
    Gender.MALE -> R.string.label_profile_gender_male
    Gender.FEMALE -> R.string.label_profile_gender_female
}

/** 目标 → 文案资源。 */
@StringRes
private fun goalLabelRes(goal: Goal): Int = when (goal) {
    Goal.CUT -> R.string.label_profile_goal_cut
    Goal.BULK -> R.string.label_profile_goal_bulk
    Goal.RECOMP -> R.string.label_profile_goal_recomp
    Goal.SHAPE -> R.string.label_profile_goal_shape
    Goal.MAINTAIN -> R.string.label_profile_goal_maintain
}

/** 器械 → 文案资源。 */
@StringRes
private fun equipmentLabelRes(equipment: Equipment): Int = when (equipment) {
    Equipment.NONE -> R.string.equipment_none
    Equipment.DUMBBELL -> R.string.equipment_dumbbell
    Equipment.BARBELL -> R.string.equipment_barbell
    Equipment.YOGA_MAT -> R.string.equipment_yoga_mat
    Equipment.PULLUP_BAR -> R.string.equipment_pullup_bar
    Equipment.RESISTANCE_BAND -> R.string.equipment_resistance_band
    Equipment.MACHINE -> R.string.equipment_machine
    Equipment.TREADMILL -> R.string.equipment_treadmill
}

/** 伤病部位 → 文案资源。 */
@StringRes
private fun injuryLabelRes(area: InjuryArea): Int = when (area) {
    InjuryArea.KNEE -> R.string.injury_knee
    InjuryArea.LOWER_BACK -> R.string.injury_lower_back
    InjuryArea.SHOULDER -> R.string.injury_shoulder
    InjuryArea.WRIST -> R.string.injury_wrist
    InjuryArea.ELBOW -> R.string.injury_elbow
    InjuryArea.ANKLE -> R.string.injury_ankle
    InjuryArea.NECK -> R.string.injury_neck
    InjuryArea.HIP -> R.string.injury_hip
    InjuryArea.CARDIO -> R.string.injury_cardio
}

/** 饮食忌口 → 文案资源。 */
@StringRes
private fun restrictionLabelRes(restriction: DietRestriction): Int = when (restriction) {
    DietRestriction.PEANUT -> R.string.restriction_peanut
    DietRestriction.SEAFOOD -> R.string.restriction_seafood
    DietRestriction.DAIRY -> R.string.restriction_dairy
    DietRestriction.GLUTEN -> R.string.restriction_gluten
    DietRestriction.SPICY -> R.string.restriction_spicy
    DietRestriction.ALCOHOL -> R.string.restriction_alcohol
}

/** 分区标题。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 引导条：可点击的提示卡片，可选附加按钮。 */
@Composable
private fun TipCard(
    text: String,
    onClick: () -> Unit,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (actionText != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(text = actionText)
                }
            }
        }
    }
}

/** 主题 → 文案资源。 */
@StringRes
private fun themeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
    ThemeMode.SYSTEM -> R.string.settings_theme_system
}

/** 单位制 → 文案资源。 */
@StringRes
private fun unitLabelRes(system: UnitSystem): Int = when (system) {
    UnitSystem.METRIC -> R.string.settings_unit_metric
    UnitSystem.IMPERIAL -> R.string.settings_unit_imperial
}

/** 弹出系统时间选择器。 */
private fun showTimePicker(
    context: Context,
    hour: Int,
    minute: Int,
    onPicked: (Int, Int) -> Unit,
) {
    TimePickerDialog(
        context,
        { _, pickedHour, pickedMinute -> onPicked(pickedHour, pickedMinute) },
        hour,
        minute,
        true,
    ).show()
}

/** `HH:mm`（纯数字，无硬编码中文）。 */
private fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

/** 是否已授予通知权限（API 33+ 需运行时权限；低版本视为已授予）。 */
private fun isNotificationGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return true
    }
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

/** 是否可调度精确闹钟（API 31+ 需系统授予；低版本恒可）。 */
private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return true
    }
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return alarmManager.canScheduleExactAlarms()
}

/** 跳系统「闹钟与提醒」设置（精确闹钟引导）。 */
private fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        .setData(Uri.parse("package:${context.packageName}"))

/** 跳应用通知设置。 */
private fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
