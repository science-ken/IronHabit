package com.ironhabit.app.ui.screens.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R

/**
 * 「问教练」区块（子项 A）—— 输入框 + 发送按钮 + 最近几轮问答气泡。
 *
 * **诚实三态**（硬要求）：
 * - 未联网（[canAsk] == false）→ 显示既有的诚实禁用说明 `ai_freechat_disabled`（**保留原文案**），
 *   不展示输入框，**绝不本地编造回答**；
 * - 在线 → 输入框可用；
 * - [isAsking] → 按钮禁用 + 进行中提示。
 *
 * 组件无状态：全部状态来自 [AiCoachViewModel]（问答不落库，只在内存里保留最近若干轮）。
 */
@Composable
fun CoachChatCard(
    canAsk: Boolean,
    messages: List<CoachChatMessage>,
    input: String,
    isAsking: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_chat_section_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (!canAsk) {
                // 离线 / 未配 Key：保持诚实的禁用说明（原文案），不渲染输入框。
                Text(
                    text = stringResource(R.string.ai_freechat_disabled),
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            Text(
                text = stringResource(R.string.ai_chat_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (messages.isEmpty()) {
                Text(
                    text = stringResource(R.string.ai_chat_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                messages.forEach { message -> ChatBubble(message = message) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    enabled = !isAsking,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(text = stringResource(R.string.ai_chat_input_placeholder)) },
                )
                Button(
                    onClick = onSend,
                    enabled = !isAsking && input.isNotBlank(),
                ) {
                    Text(text = stringResource(R.string.ai_chat_send))
                }
            }

            if (isAsking) {
                Text(
                    text = stringResource(R.string.ai_chat_in_progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 单条气泡：左边固定宽度标签（你 / 教练），右边正文；诚实态用专门文案。 */
@Composable
private fun ChatBubble(message: CoachChatMessage) {
    val label: String = stringResource(
        if (message.kind == CoachChatKind.USER) R.string.ai_chat_label_you else R.string.ai_chat_label_coach,
    )
    val text: String = when (message.kind) {
        CoachChatKind.USER, CoachChatKind.ANSWER -> message.text
        CoachChatKind.NEEDS_NETWORK -> stringResource(R.string.ai_chat_needs_network)
        CoachChatKind.FAILED -> stringResource(R.string.ai_chat_failed)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(44.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 「问教练」气泡的**发送方 / 状态**（UI 模型，不是 `strings.xml` 的文案键）。
 *
 * - [USER] 用户提问（[CoachChatMessage.text] 是用户输入，属**数据**不进 `strings.xml`）；
 * - [ANSWER] AI 回答正文（同上，是模型返回的**数据**）；
 * - [NEEDS_NETWORK] 未联网（开关关 / 无 Key）→ 显示固定文案，**绝不本地编造回答**；
 * - [FAILED] 已联网但失败（网络 / 超时 / 空响应）→ 显示固定文案，可重发。
 */
enum class CoachChatKind {
    USER,
    ANSWER,
    NEEDS_NETWORK,
    FAILED,
}

/**
 * 一条问答气泡（**只在内存里保留最近若干轮，问答不落库**）。
 *
 * @property kind 发送方 / 状态
 * @property text 正文；仅 [CoachChatKind.USER] / [CoachChatKind.ANSWER] 使用
 *   （[CoachChatKind.NEEDS_NETWORK] / [CoachChatKind.FAILED] 一律用固定文案，见 [ChatBubble]）
 */
data class CoachChatMessage(
    val kind: CoachChatKind,
    val text: String = "",
)
