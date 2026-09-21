package com.ironhabit.app.ui.screens.planpreview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.ui.components.LocalSnackbarHostState
import com.ironhabit.app.ui.theme.IronHabitSpacing

/** 周一~周日（下标 0 = 周一）。 */
private val WEEKDAY_RES = listOf(
    R.string.weekday_mon, R.string.weekday_tue, R.string.weekday_wed, R.string.weekday_thu,
    R.string.weekday_fri, R.string.weekday_sat, R.string.weekday_sun,
)

/**
 * 「本周计划预览」页 —— 生成之后、写库之前停在这里，用户逐天点「采纳这天」。
 *
 * 一天都没采纳就返回 = **库里一条都没动**。
 */
@Composable
fun PlanPreviewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlanPreviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current

    // 「已写入 N 条计划」以前从来没被看见过：ViewModel 一直在设 snackbarRes，
    // 也有 onConsumeSnackbar()，但这一页过去根本没接 Snackbar host。
    val snackbarText: String? = uiState.snackbarRes?.let { res ->
        uiState.snackbarArg?.let { arg -> stringResource(res, arg) } ?: stringResource(res)
    }
    LaunchedEffect(snackbarText) {
        if (snackbarText != null) {
            snackbarHostState.showSnackbar(snackbarText)
            viewModel.onConsumeSnackbar()
        }
    }

    LaunchedEffect(uiState.finished) {
        if (uiState.finished) onBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = IronHabitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.title_plan_preview, uiState.weekRange),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // 诚实标注：只有真的走了远端才写「AI 生成」，本地规则必须说清是本地规则。
            Text(
                text = stringResource(
                    if (uiState.sourceIsAi) R.string.plan_preview_source_ai else R.string.plan_preview_source_local,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.plan_preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 模型每次都写好了这段「为什么这么排」，以前这一页 grep analysis 零命中 ——
        // 等于白花钱生成再扔掉。本地规则不产中文（LocalRuleAdvisor 只吐资源名），
        // 所以 analysis 为 null 时整块不显示，不拿规则文案硬凑一段看起来像 AI 写的话。
        val analysis: String? = uiState.analysis
        if (!analysis.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(IronHabitSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
                ) {
                    Text(
                        text = stringResource(R.string.plan_preview_analysis_label),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = analysis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (uiState.preservedCount > 0) {
            Text(
                text = stringResource(R.string.plan_preview_preserved, uiState.preservedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (uiState.days.isEmpty()) {
            Text(
                text = stringResource(R.string.plan_preview_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = IronHabitSpacing.xl),
                textAlign = TextAlign.Center,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            items(uiState.days, key = { day -> day.dayOfWeek }) { day ->
                DayCard(
                    day = day,
                    weekday = WEEKDAY_RES[day.dayOfWeek - 1],
                    onAdopt = { viewModel.onAdoptDay(day.dayOfWeek) },
                )
            }
            item { HorizontalDivider() }
        }

        Text(
            text = if (uiState.adoptedDays == 0) {
                stringResource(R.string.plan_preview_footer_none)
            } else {
                stringResource(
                    R.string.plan_preview_footer,
                    uiState.adoptedDays,
                    uiState.draftDays.size,
                    uiState.adoptedItemCount,
                )
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = IronHabitSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = viewModel::onCancel, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.plan_preview_cancel))
            }
            Button(
                onClick = viewModel::onAdoptAll,
                enabled = !uiState.busy && uiState.pendingDays > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(
                        R.string.plan_preview_adopt_all,
                        uiState.adoptedDays,
                        uiState.draftDays.size,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DayCard(day: PlanPreviewViewModel.Day, weekday: Int, onAdopt: () -> Unit) {
    if (day.kind != PlanPreviewViewModel.Kind.DRAFT) {
        // 休息日 / 由模板负责的天：也要在这一周里露出来，否则用户会以为预览漏了几天。
        Text(
            text = buildString {
                append(stringResource(weekday))
                append(" ")
                append(day.dateLabel)
                append(" · ")
                append(
                    stringResource(
                        if (day.kind == PlanPreviewViewModel.Kind.TEMPLATE_OWNED) {
                            R.string.plan_preview_template_day
                        } else {
                            R.string.plan_preview_rest_day
                        },
                    ),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = IronHabitSpacing.xs, vertical = IronHabitSpacing.xs),
        )
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (day.adopted) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(IronHabitSpacing.md),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(weekday) + " " + day.dateLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.plan_preview_day_meta, day.items.size, day.sets),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!day.adopted) {
                    OutlinedButton(onClick = onAdopt) {
                        Text(text = stringResource(R.string.plan_preview_adopt_day))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.plan_preview_adopted),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            day.items.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = item.goal,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
