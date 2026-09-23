package com.ironhabit.app.ui.screens.profile

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.screens.food.FoodLibrarySheet
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * Tab4「我的」页面。
 *
 * 首屏结构（方案 J）：档案卡（含完整度环）→ 关键数字四联 → 二级入口。
 * 四联那四个数字是这一页存在的理由 —— 改版前这一屏**一个数字都没有**，
 * 只有四行「身体数据 / 设置 / 数据备份 / 食物库」，两张图被挤到屏幕外。
 *
 * 两张统计图已经搬去「训练统计」二级页：它们属于"按月回顾才看"的低频数据，
 * 留在这里就把首屏撑成三屏。
 *
 * 食物库这一行**开弹层而不是跳路由**：管理界面与「今日 → 饮食 → 食物库」是同一个
 * [FoodLibrarySheet]，新开路由就要把列表/搜索/新建/停用再写一遍。
 *
 * @param onOpenBodyMetrics 身体数据入口回调
 * @param onOpenSettings 设置（含「我的档案」区块）入口回调
 * @param onOpenBackup 数据备份入口回调
 * @param onOpenTrainingStats 训练统计（柱状 / 占比 / 热力图）入口回调
 */
@Composable
fun ProfileScreen(
    onOpenBodyMetrics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenTrainingStats: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showFoodLibrary by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(IronHabitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.lg),
    ) {
        // 普通本地 val：委托属性不支持智能转换，需先取出再在分支内使用。
        val errorRes: Int? = uiState.errorRes
        when {
            uiState.isLoading -> {
                // 两块骨架与真实结构同构：档案卡与四联各约 64dp 高，
                // 加载完成后不会发生整屏跳动。
                LoadingSkeleton()
                LoadingSkeleton()
                EntryRows(
                    onOpenBodyMetrics = onOpenBodyMetrics,
                    onOpenSettings = onOpenSettings,
                    onOpenBackup = onOpenBackup,
                    onOpenTrainingStats = onOpenTrainingStats,
                    onOpenFoodLibrary = { showFoodLibrary = true },
                )
            }

            errorRes != null -> {
                EmptyState(
                    text = stringResource(errorRes),
                    actionText = stringResource(R.string.action_retry),
                    onAction = viewModel::onRetry,
                )
                // 读不到数据时也要能走开：入口行是静态的，不依赖这次的聚合。
                EntryRows(
                    onOpenBodyMetrics = onOpenBodyMetrics,
                    onOpenSettings = onOpenSettings,
                    onOpenBackup = onOpenBackup,
                    onOpenTrainingStats = onOpenTrainingStats,
                    onOpenFoodLibrary = { showFoodLibrary = true },
                )
            }

            else -> {
                ProfileArchiveCard(
                    profile = uiState.profile,
                    onClick = onOpenSettings,
                )

                ProfileStatStrip(
                    streak = uiState.trainingStreak,
                    weekCompleted = uiState.weekCompletedDays,
                    weekPlanned = uiState.weekPlannedDays,
                    totalRecords = uiState.totalCheckIns,
                    latestWeight = uiState.latestWeight,
                )

                EntryRows(
                    onOpenBodyMetrics = onOpenBodyMetrics,
                    onOpenSettings = onOpenSettings,
                    onOpenBackup = onOpenBackup,
                    onOpenTrainingStats = onOpenTrainingStats,
                    onOpenFoodLibrary = { showFoodLibrary = true },
                )
            }
        }
    }

    if (showFoodLibrary) {
        FoodLibrarySheet(onDismissRequest = { showFoodLibrary = false })
    }
}

/**
 * 五个二级入口。
 *
 * 加载 / 错误 / 内容三个分支都要出现，所以单独成一个函数而不是抄三遍。
 * 第 3 步会把它们换成带摘要的「记录台账」行 —— 那一版里每行右侧要挂真条数。
 */
@Composable
private fun EntryRows(
    onOpenBodyMetrics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenTrainingStats: () -> Unit,
    onOpenFoodLibrary: () -> Unit,
) {
    EntryRow(
        text = stringResource(R.string.entry_body_metrics),
        onClick = onOpenBodyMetrics,
    )
    HorizontalDivider()
    EntryRow(
        text = stringResource(R.string.entry_training_stats),
        onClick = onOpenTrainingStats,
    )
    HorizontalDivider()
    EntryRow(
        text = stringResource(R.string.entry_settings),
        onClick = onOpenSettings,
    )
    HorizontalDivider()
    EntryRow(
        text = stringResource(R.string.entry_backup),
        onClick = onOpenBackup,
    )
    HorizontalDivider()
    EntryRow(
        text = stringResource(R.string.entry_food_library),
        onClick = onOpenFoodLibrary,
    )
    HorizontalDivider()
}

@Composable
private fun EntryRow(
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = IronHabitSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
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
