package com.ironhabit.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseSource

/**
 * 动作来源标签：只给**非内置**动作打标（`自建` / `AI 推荐`）。
 *
 * 内置动作是绝大多数，若也给它们打「内置」标签，列表会被无信息量的角标淹没；
 * 用户真正需要分辨的是「哪个是我自己加的」「哪个是 AI 推荐的」，所以只标这两种。
 */
@Composable
fun ExerciseSourceChip(
    source: ExerciseSource,
    modifier: Modifier = Modifier,
) {
    when (source) {
        ExerciseSource.CUSTOM -> SourceChip(
            text = stringResource(R.string.label_source_custom),
            modifier = modifier,
        )

        ExerciseSource.AI_SUGGESTED -> SourceChip(
            text = stringResource(R.string.label_source_ai),
            modifier = modifier,
        )

        ExerciseSource.BUILT_IN -> Unit // 内置不打标
    }
}

@Composable
private fun SourceChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    SuggestionChip(
        onClick = { /* 纯展示，不可点 */ },
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        modifier = modifier,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        border = null,
    )
}

/** 动作来源 → 文案资源（筛选条等需要纯文案的场景用）。 */
fun exerciseSourceLabelRes(source: ExerciseSource): Int = when (source) {
    ExerciseSource.BUILT_IN -> R.string.label_source_built_in
    ExerciseSource.CUSTOM -> R.string.label_source_custom
    ExerciseSource.AI_SUGGESTED -> R.string.label_source_ai
}

/** 是否要为该动作显示来源标签（内置不显示）。 */
fun Exercise.hasVisibleSourceChip(): Boolean = source != ExerciseSource.BUILT_IN
