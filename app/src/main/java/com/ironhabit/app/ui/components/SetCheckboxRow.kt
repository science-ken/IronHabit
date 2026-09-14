package com.ironhabit.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.MAX_SETS

/**
 * 逐组勾选行：`totalSets` 个编号复选框，第 [index] 组（0-based）的勾选态由 [mask] 的第 `index` 位决定。
 *
 * `mask` 就是 `check_ins.completed_sets_mask`（唯一真源），本组件**只读 mask、只回调索引**，
 * 绝不在此处做位运算写库（写入由 [com.ironhabit.app.domain.usecase.ToggleSetUseCase] 统一保证不变量）。
 *
 * **越界保护**：`totalSets` 会被钳制到 `0..MAX_SETS`（`Int` 位宽上限 31），
 * 与 schema-v2 §2.3 的「钳制而非崩溃」一致。
 *
 * @param totalSets 计划目标组数
 * @param mask 已完成位图（bit i = 第 i+1 组完成）
 * @param onToggle 点击第 [index] 组（0-based）的回调
 * @param enabled 是否可交互（例如卡片已完成只读时传 `false`）
 */
@Composable
fun SetCheckboxRow(
    totalSets: Int,
    mask: Int,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val count = totalSets.coerceIn(0, MAX_SETS)
    if (count == 0) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until count) {
            val done = (mask shr index) and 1 == 1
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 2.dp),
            ) {
                Checkbox(
                    checked = done,
                    onCheckedChange = { onToggle(index) },
                    enabled = enabled,
                )
                Text(
                    text = (index + 1).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 逐组勾选行的无障碍文案（供调用方按需使用）。 */
@Composable
fun setCheckboxContentDescription(index: Int): String =
    stringResource(R.string.cd_set_index, index + 1)
