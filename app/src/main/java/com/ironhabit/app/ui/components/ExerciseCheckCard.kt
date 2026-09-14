package com.ironhabit.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.TodayPlanItem

/**
 * 今日训练打卡卡片。
 *
 * 交互：
 * - **点击卡片主体**：未完成 → [onQuickCheckIn]（一键打卡）；已完成 → [onUndo]（撤销）；
 * - **长按卡片**：展开补录 → [onOpenSheet]；
 * - 右侧「一键打卡」/「撤销」按钮 → [onQuickCheckIn] / [onUndo]（与点卡片同义的**可见入口**）；
 * - 「补录详情」按钮 → [onOpenSheet]；右侧详情图标 → [onOpenDetail]（其 `contentDescription`
 *   使用 `title_exercise_detail`，与"打开动作详情"的行为保持一致）；
 * - **逐组勾选行**（v2）：勾选/取消第 i 组 → [onToggleSet]（0-based）；
 * - **RPE 行**（v2）：点 1~10 → [onSetRpe]。仅在已有打卡记录时显示（无组次谈不上强度）。
 *
 * 已完成时整卡置灰（`surfaceVariant`）并显示勾选图标与 `label_today_done`。
 *
 * @param onToggleSet 勾选/取消第 [Int] 组（0-based）
 * @param onSetRpe 写入 RPE 强度（`1..10`）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExerciseCheckCard(
    item: TodayPlanItem,
    onQuickCheckIn: () -> Unit,
    onUndo: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleSet: (Int) -> Unit = {},
    onSetRpe: (Int) -> Unit = {},
) {
    val colorScheme = MaterialTheme.colorScheme
    val completed = item.isCompleted
    val containerColor = if (completed) colorScheme.surfaceVariant else colorScheme.surface
    val contentColor = if (completed) colorScheme.onSurfaceVariant else colorScheme.onSurface
    val mask = item.checkIn?.completedSetsMask ?: 0

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (completed) onUndo() else onQuickCheckIn() },
                onLongClick = onOpenSheet,
            ),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (completed) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.cd_checkin_done),
                            tint = colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = item.exercise.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    text = targetText(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )

                // 逐组勾选（v2）：mask 为唯一真源，点击回调只传索引。
                SetCheckboxRow(
                    totalSets = item.plan.targetSets,
                    mask = mask,
                    onToggle = onToggleSet,
                )

                // RPE（v2）：渐进超负荷输入源；已有打卡时才显示。
                if (item.checkIn != null) {
                    RpeChips(
                        current = item.checkIn.rpe,
                        onSelect = onSetRpe,
                    )
                }

                Text(
                    text = stringResource(
                        if (completed) R.string.label_today_done else R.string.label_today_pending
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (completed) colorScheme.primary else colorScheme.onSurfaceVariant,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                // 可见的一键打卡 / 撤销入口（P0-3：一键打卡零摩擦）
                TextButton(onClick = { if (completed) onUndo() else onQuickCheckIn() }) {
                    Text(
                        text = stringResource(
                            if (completed) R.string.action_undo else R.string.action_quick_checkin
                        )
                    )
                }
                TextButton(onClick = onOpenSheet) {
                    Text(text = stringResource(R.string.action_detailed))
                }
                IconButton(onClick = onOpenDetail) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        // 图标行为 = 打开动作详情，contentDescription 必须与行为一致
                        contentDescription = stringResource(R.string.title_exercise_detail),
                    )
                }
            }
        }
    }
}

/** RPE 1~10 选择行（横向可滚动）。 */
@Composable
private fun RpeChips(
    current: Int?,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.hint_rpe),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (rpe in MIN_RPE..MAX_RPE) {
            FilterChip(
                selected = current == rpe,
                onClick = { onSelect(rpe) },
                label = { Text(text = rpe.toString()) },
            )
        }
    }
}

/** 目标组数 × 次数（纯数字，避免硬编码中文文案）。 */
private fun targetText(item: TodayPlanItem): String {
    val sets = item.plan.targetSets
    val reps = item.plan.targetReps
    return "$sets × $reps"
}

/** RPE 合法区间下界。 */
private const val MIN_RPE = 1

/** RPE 合法区间上界。 */
private const val MAX_RPE = 10
