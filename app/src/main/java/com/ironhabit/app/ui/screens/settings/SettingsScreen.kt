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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.BuildConfig
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.ThemeMode
import com.ironhabit.app.domain.model.UnitSystem
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.LocalSnackbarHostState

/**
 * 「设置」页：主题 / 单位 / 每日提醒（含精确闹钟与通知权限引导）/ 隐私 / 版本 / 备份入口。
 *
 * 精确闹钟引导（架构 §2.6）：API 31+ 且未授予 `SCHEDULE_EXACT_ALARM`、且提醒已开启时，
 * 在提醒开关下方展示引导条（点击跳系统「闹钟与提醒」设置）；用户拒绝则由 `ReminderSchedulerImpl` 自动降级。
 *
 * @param onOpenBackup 数据备份入口回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
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
private fun themeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
    ThemeMode.SYSTEM -> R.string.settings_theme_system
}

/** 单位制 → 文案资源。 */
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
