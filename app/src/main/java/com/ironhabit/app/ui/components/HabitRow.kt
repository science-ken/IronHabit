package com.ironhabit.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 习惯勾选行。
 *
 * 左侧显示 emoji 与习惯名（下方小字显示连续天数 `label_streak_days`），
 * 右侧为 [Checkbox]（勾选/取消 → [onToggle]）与编辑按钮（→ [onEdit]）、删除按钮（→ [onDelete]）。
 *
 * **只读态**（[enabled] = `false`，用于「所选日 > 今天」）：禁用勾选（打卡）入口，
 * 但「编辑」（改习惯定义，与日期无关）仍可用。
 *
 * @param enabled 勾选（打卡）是否可写（`false` = 未来日只读态）
 */
@Composable
fun HabitRow(
    item: HabitItem,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = IronHabitSpacing.xs, vertical = IronHabitSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.habit.emoji,
                style = MaterialTheme.typography.headlineSmall,
            )
            Column(
                modifier = Modifier.padding(start = IronHabitSpacing.md),
                verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xxs),
            ) {
                Text(
                    text = item.habit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.label_streak_days, item.streak.current),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurfaceVariant,
                )
                // v2：计量型习惯显示目标值（如「8杯」「30分钟」）；纯勾选型无目标值则不显示。
                val targetValue = item.habit.targetValue
                if (targetValue != null) {
                    val unit: String = item.habit.targetUnit.orEmpty()
                    Text(
                        // 单位是自由文本，填成数字时直接拼会读成一个数（「4」+「2」→「42」），
                        // 所以中间加间隔点；没有单位时不加，免得留个孤零零的「 · 」。
                        text = formatTarget(targetValue) + if (unit.isEmpty()) "" else " · $unit",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.primary,
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.action_edit),
                )
            }
            Checkbox(
                checked = item.isCompletedToday,
                onCheckedChange = onToggle,
                enabled = enabled,
            )
        }
    }
}

/** 目标值 → 展示文本（整数去掉小数点，避免显示成 "8.0"）。 */
private fun formatTarget(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
