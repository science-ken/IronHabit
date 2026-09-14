package com.ironhabit.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R

/**
 * 统一空态占位（图标 + 文案 + 可选引导按钮）。
 *
 * 用于「加载完成但无数据」的场景；加载中请用 [LoadingSkeleton]，加载失败给 [actionText] = `action_retry`。
 *
 * @param text 主文案（由调用方 `stringResource(empty_*)` 取得）
 * @param actionText 引导按钮文案，`null` 时隐藏按钮
 * @param onAction 引导按钮点击回调，仅当两者均非空时展示
 */
@Composable
fun EmptyState(
    text: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Inbox,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (actionText != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(text = actionText)
            }
        }
    }
}

/** 便捷重载：按钮文案固定为「重试」（加载失败场景）。 */
@Composable
fun EmptyStateRetry(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        text = text,
        actionText = stringResource(R.string.action_retry),
        onAction = onRetry,
        modifier = modifier,
    )
}
