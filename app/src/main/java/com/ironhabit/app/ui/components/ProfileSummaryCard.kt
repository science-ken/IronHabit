package com.ironhabit.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.UserProfile

/**
 * 身体档案概要卡（**只读**、**纯展示**）：标题 + `性别 · 年龄 · 目标` 概要 + `›`。
 *
 * 供「我的」页与「AI 教练」页**共用同一份实现**（避免两处各写一遍导致显示口径漂移，
 * 上一轮「计划目标重量」就出过这种 bug）。
 *
 * 设计约束（**后续修改请遵守**）：
 * - **纯展示**：只读 [UserProfile] 派生文案 + 一个点击回调；
 * - **不得**内含任何页面专属逻辑（不得硬编码跳转目标、不得含 AI 页专属文案）——
 *   跳哪里由调用方通过 [onClick] 决定；
 * - 若某个页面需要额外信息（如器械 / 伤病提示），一律**在卡片外面**另加区块，
 *   **不许**往本组件塞参数分支。
 *
 * 目标恒有值（默认 [Goal.MAINTAIN]），故概要**永不为空**。
 *
 * @param profile 用户档案（未填字段自动省略，不显示占位符）
 * @param onClick 点击回调（跳转目标由调用方决定）
 */
@Composable
fun ProfileSummaryCard(
    profile: UserProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val genderText = profile.gender?.let { stringResource(genderLabelRes(it)) }
    val ageText = profile.age?.let { "${it}${stringResource(R.string.suffix_profile_age)}" }
    val goalText = stringResource(goalLabelRes(profile.goal))
    val summary = listOfNotNull(genderText, ageText, goalText).joinToString(SUMMARY_SEPARATOR)

    Card(
        modifier = modifier
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
