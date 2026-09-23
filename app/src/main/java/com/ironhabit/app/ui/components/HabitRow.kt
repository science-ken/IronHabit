package com.ironhabit.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.ui.theme.habitColor
import com.ironhabit.app.ui.theme.IronHabitShapes
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 习惯勾选行。
 *
 * 最左侧一条 4dp **主题色色条**（`Habit.colorHex`）—— 这是那个颜色在全 app 唯一的渲染点。
 * 以前选色器让用户从六个颜色里挑一个，挑完之后除了选色器自己那个圆点，界面上哪儿都不变
 * （审查报告 A9 的后半段，见 `defects.md` D19）。
 * 为什么是色条而不是给 emoji 加个彩色圆底：emoji 本身是彩色字形，压在紫色/橙色上会糊成一团，
 * 反而把颜色读没了；色条不跟任何人抢地方，自律页与今日页一次同时生效。
 *
 * 左侧显示 emoji 与习惯名（下方小字显示连续天数 `label_streak_days`），
 * 右侧为编辑按钮（→ [onEdit]）、[Checkbox]（勾选/取消 → [onToggle]），
 * 以及**只在调用方给了 [onDelete] 时**才出现的删除按钮（最右，离勾选最远）。
 *
 * **只读态**（[enabled] = `false`，用于「所选日 > 今天」）：禁用勾选（打卡）入口，
 * 但「编辑」与「删除」（改的是习惯定义本身，与日期无关）仍可用。
 *
 * @param enabled 勾选（打卡）是否可写（`false` = 未来日只读态）
 * @param onDelete `null` = 这个位置不该有删除入口。刻意不给默认空实现：
 *   画一个点了没反应的垃圾桶，比不画更糟 —— 之前正是 `onDelete = {}` 这个默认值让删除按钮
 *   一直没被渲染，而今日页复用本组件时又确实不该删习惯（审查报告 P0-2）。
 */
@Composable
fun HabitRow(
    item: HabitItem,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 色条要 `fillMaxHeight`，而 Row 默认按内容高测量 → 先用 IntrinsicSize.Min 量出来
            // （与今日页磁贴、我的页四联同一处理）。
            .height(IntrinsicSize.Min)
            .padding(horizontal = IronHabitSpacing.xs, vertical = IronHabitSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(COLOR_BAR_WIDTH)
                .clip(IronHabitShapes.full)
                .background(habitColor(item.habit.colorHex)),
        )

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
            // 放最右：与勾选框隔开一个控件的距离，误触概率最低。
            // 确认框在调用方（DisciplineScreen）—— 本组件是今日页与自律页共用的哑组件。
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                    )
                }
            }
        }
    }
}

/** 目标值 → 展示文本（整数去掉小数点，避免显示成 "8.0"）。 */
private fun formatTarget(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** 主题色色条宽度：组件固有规格（再细就读不成"一个颜色"，再粗就抢 emoji 的位置）。 */
private val COLOR_BAR_WIDTH = 4.dp
