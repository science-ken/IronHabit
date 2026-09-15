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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.ui.components.CategoryPieChart
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.ProfileSummaryCard
import com.ironhabit.app.ui.components.TrendChart

/**
 * Tab4「我的」页面：身体档案概要卡（跳设置页档案区）+ 二级入口（身体数据 / 设置 / 备份）
 * + 近 30 天趋势图 + 训练类型占比饼图。
 *
 * @param onOpenBodyMetrics 身体数据入口回调
 * @param onOpenSettings 设置（含「我的档案」区块）入口回调
 * @param onOpenBackup 数据备份入口回调
 */
@Composable
fun ProfileScreen(
    onOpenBodyMetrics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProfileSummaryCard(
            profile = uiState.profile,
            onClick = onOpenSettings,
        )

        EntryRow(
            text = stringResource(R.string.entry_body_metrics),
            onClick = onOpenBodyMetrics,
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

        val errorRes: Int? = uiState.errorRes
        when {
            uiState.isLoading -> {
                LoadingSkeleton()
                LoadingSkeleton()
            }

            errorRes != null -> {
                EmptyState(
                    text = stringResource(errorRes),
                    actionText = stringResource(R.string.action_retry),
                    onAction = { /* 数据流为 Room 响应式，重进页面即刷新 */ },
                )
            }

            else -> {
                SectionTitle(text = stringResource(R.string.title_trend_chart))
                TrendChart(points = uiState.trend)

                SectionTitle(text = stringResource(R.string.title_category_chart))
                CategoryPieChart(shares = uiState.categoryShare)
            }
        }
    }
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
            .padding(vertical = 14.dp),
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

