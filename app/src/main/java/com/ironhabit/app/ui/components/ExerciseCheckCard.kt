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
import androidx.compose.material.icons.filled.Edit
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
 * **只读态**（[enabled] = `false`，用于「所选日 > 今天」）：禁用**全部写入口**
 * （一键打卡 / 撤销 / 补录弹层 / 逐组勾选 / RPE），整卡置灰给出可见反馈；
 * 「动作详情」（只读）仍可打开。
 *
 * 计划目标文案（`组数 × 次数`，含目标重量）由 [planGoalText] 统一生成，与训练页计划行一致。
 *
 * @param enabled 是否可写（`false` = 未来日只读态，禁用所有写入口）
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
    enabled: Boolean = true,
    onToggleSet: (Int) -> Unit = {},
    onSetRpe: (Int) -> Unit = {},
    /** 编辑本条计划（跳转计划编辑页）；未来日只读时由调用方禁用。 */
    onEditPlan: () -> Unit = {},
) {
    val colorScheme = MaterialTheme.colorScheme
    val completed = item.isCompleted
    val dimmed = completed || !enabled
    val containerColor = if (dimmed) colorScheme.surfaceVariant else colorScheme.surface
    val contentColor = if (dimmed) colorScheme.onSurfaceVariant else colorScheme.onSurface
    val mask = item.checkIn?.completedSetsMask ?: 0
    val goalText = planGoalText(
        sets = item.plan.targetSets,
        reps = item.plan.targetReps,
        weightKg = item.plan.targetWeightKg,
        durationMin = item.plan.targetDurationMin,
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
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
                    // 自建 / AI 推荐动作打标，一眼看出这条来自哪里
                    if (item.exercise.hasVisibleSourceChip()) {
                        ExerciseSourceChip(source = item.exercise.source)
                    }
                }
                Text(
                    text = goalText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )

                // 逐组勾选（v2）：mask 为唯一真源，点击回调只传索引。（未来日只读时禁用）
                SetCheckboxRow(
                    totalSets = item.plan.targetSets,
                    mask = mask,
                    onToggle = onToggleSet,
                    enabled = enabled,
                )

                // RPE（v2）：渐进超负荷输入源；已有打卡时才显示。（未来日只读时禁用）
                if (item.checkIn != null) {
                    RpeChips(
                        current = item.checkIn.rpe,
                        onSelect = onSetRpe,
                        enabled = enabled,
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
                TextButton(
                    onClick = { if (completed) onUndo() else onQuickCheckIn() },
                    enabled = enabled,
                ) {
                    Text(
                        text = stringResource(
                            if (completed) R.string.action_undo else R.string.action_quick_checkin
                        )
                    )
                }
                TextButton(onClick = onOpenSheet, enabled = enabled) {
                    Text(text = stringResource(R.string.action_detailed))
                }
                // 详情页为只读，未来日仍可打开（不禁用）。
                IconButton(onClick = onOpenDetail) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        // 图标行为 = 打开动作详情，contentDescription 必须与行为一致
                        contentDescription = stringResource(R.string.title_exercise_detail),
                    )
                }
                // 编辑本条计划（组数/次数/重量）。未来日为只读 → 不给编辑。
                IconButton(onClick = onEditPlan, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        // 图标行为 = 编辑本条计划，contentDescription 必须与行为一致
                        contentDescription = stringResource(R.string.action_edit_plan),
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
    enabled: Boolean,
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
                enabled = enabled,
            )
        }
    }
}

/** RPE 合法区间下界。 */
private const val MIN_RPE = 1

/** RPE 合法区间上界。 */
private const val MAX_RPE = 10
