package com.ironhabit.app.ui.screens.profile

import androidx.annotation.StringRes
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
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.ui.components.CategoryPieChart
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
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

/**
 * 身体档案概要卡（只读）：标题 + `性别 · 年龄 · 目标` 概要 + `›`，点击跳设置页「我的档案」区块。
 *
 * 目标恒有值（默认保持），故概要**永不为空**。
 */
@Composable
private fun ProfileSummaryCard(
    profile: UserProfile,
    onClick: () -> Unit,
) {
    val genderText = profile.gender?.let { stringResource(genderLabelRes(it)) }
    val ageText = profile.age?.let { "${it}${stringResource(R.string.suffix_profile_age)}" }
    val goalText = stringResource(goalLabelRes(profile.goal))
    val summary = listOfNotNull(genderText, ageText, goalText).joinToString(SUMMARY_SEPARATOR)

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.entry_profile_edit),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** 概要分隔符（纯符号，非中文文案）。 */
private const val SUMMARY_SEPARATOR = " · "
