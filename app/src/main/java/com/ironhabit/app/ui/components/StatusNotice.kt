package com.ironhabit.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.ironhabit.app.ui.theme.IronHabitShapes
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 状态提示条：有容器、有图标的一句"这一天是怎么回事"。
 *
 * 以前今日页的「还没到这一天」与「今天是休息日」是裸 `Text`（审查报告 三.3）。
 * 裸文本在这一页同时充当分区标题、数值与说明三种身份，读者分不出这句是**状态**还是正文；
 * 给它底色和图标之后，它和磁贴、按钮一样是一块可指认的东西。
 *
 * 图标是装饰：`contentDescription = null` 让它整个节点不进无障碍树，TalkBack 只念文字
 * （与 `TodayBento` 里那个「›」同一处理，B1 那条缺陷的成因就是把它写成了文本）。
 */
@Composable
fun StatusNotice(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(IronHabitShapes.card)
            .background(colorScheme.secondaryContainer)
            .padding(horizontal = IronHabitSpacing.md, vertical = IronHabitSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.onSecondaryContainer,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = colorScheme.onSecondaryContainer,
        )
    }
}
