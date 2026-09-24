package com.ironhabit.app.ui.screens.planpreview

import androidx.annotation.StringRes
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.domain.ai.external.ProfileFieldDiff
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.ui.components.INJURY_SEPARATOR
import com.ironhabit.app.ui.components.LocalSnackbarHostState
import com.ironhabit.app.ui.components.SUMMARY_SEPARATOR
import com.ironhabit.app.ui.components.equipmentLabelRes
import com.ironhabit.app.ui.components.goalLabelRes
import com.ironhabit.app.ui.components.injuryLabelRes
import com.ironhabit.app.ui.components.joinLabels
import com.ironhabit.app.ui.screens.ai.ImportPlanNoteList
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
            // 诚实标注：「AI 生成」只留给 app 真的联网发出去的那一次请求。
            // `when` 穷尽、不写 else —— 新增来源忘了配文案要编译不过，而不是被静默归进「本地规则」。
            Text(
                text = stringResource(
                    when (uiState.source) {
                        AdviceSource.LOCAL_RULES -> R.string.plan_preview_source_local
                        AdviceSource.REMOTE_LLM -> R.string.plan_preview_source_ai
                        AdviceSource.EXTERNAL_AI_IMPORT -> R.string.plan_preview_source_external
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ⚠️ 标题以下**全部**放进同一个滚动容器。以前顶部这几块（提示 / 覆盖警告 / 清单 /
        // 教练说明）是不滚的固定内容，而下面的计划列表用 `weight(1f)` 只吃剩下的空间 ——
        // 外部 AI 写的「教练说明」动辄四五句，一长就把列表压成一条半，用户既滑不动上面的字，
        // 也看不到后面几天的计划（真机反馈：「文字无法下滑导致看计划很局限」）。
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
        ) {
            // 断网或远端报错时结果同样是本地规则，而这页原来只写「本地规则」三个字 ——
            // 用户白等一次超时却读不到原因。AI 教练屏早就有这句话（`SourceLine`），同一件事说同一句。
            if (uiState.fellBackFromRemote) {
                item {
                    Text(
                        text = stringResource(R.string.ai_source_fallback),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.plan_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.source == AdviceSource.EXTERNAL_AI_IMPORT) {
                // 外部文档导入的两句话，都必须在**点采纳之前**读到：
                // 一句是"这些条以后会被重新生成覆盖"（不说=用户以为问回来的计划从此归他保管），
                // 一句是逐条"什么没导进来"（不列=他只会发现这周莫名少了两条）。
                item {
                    Text(
                        text = stringResource(R.string.plan_preview_import_overwrite),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { ImportPlanNoteList(notes = uiState.importNotes) }
                // 档案 diff 也在这同一屏：它和"这几天的计划"是同一份文档带来的两件事，
                // 拆成两屏会让人点两次"确定"却只表达了一个意图。
                if (uiState.profileRows.isNotEmpty()) {
                    item {
                        ProfileDiffBlock(
                            rows = uiState.profileRows,
                            enabled = !uiState.busy,
                            onToggle = viewModel::onToggleProfileField,
                            onApply = viewModel::onApplyProfile,
                        )
                    }
                }
            }
            // 模型每次都写好了这段「为什么这么排」，以前这一页 grep analysis 零命中 ——
            // 等于白花钱生成再扔掉。本地规则不产中文（LocalRuleAdvisor 只吐资源名），
            // 所以 analysis 为 null 时整块不显示，不拿规则文案硬凑一段看起来像 AI 写的话。
            val analysis: String? = uiState.analysis
            if (!analysis.isNullOrBlank()) {
                item {
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
            }
            if (uiState.preservedCount > 0) {
                item {
                    Text(
                        text = stringResource(R.string.plan_preview_preserved, uiState.preservedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (uiState.days.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.plan_preview_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = IronHabitSpacing.xl),
                        textAlign = TextAlign.Center,
                    )
                }
            }

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
                // 采纳过之后这个按钮不再"不写入" —— 已采纳的那几天已经在库里了，
                // 它实际做的是"收工离开"。沿用旧文案等于当面否认上一行的「已采纳 N / M 天」。
                Text(
                    text = stringResource(
                        if (uiState.adoptedDays == 0) {
                            R.string.plan_preview_cancel
                        } else {
                            R.string.plan_preview_done
                        },
                    ),
                )
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
            // 外部 AI 给每条动作写的"为什么"。默认折起来 —— 它是文字，不该和组数次数抢视觉；
            // 但必须留一个入口，那正是用户把外部模型换掉内置模型的主要理由。
            val explainedCount: Int = day.items.count { item -> !item.explanation.isNullOrBlank() }
            var reasonsExpanded by remember(day.dayOfWeek) { mutableStateOf(false) }
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
                // 理由跟在**它解释的那一行**下面，而不是另起一块清单：
                // 分开放的话用户得自己在两处之间对号，那是"为什么是它"最没用的呈现方式。
                if (reasonsExpanded) {
                    item.explanation?.let { reason ->
                        Text(
                            text = "· $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(bottom = IronHabitSpacing.xs),
                        )
                    }
                }
            }
            if (explainedCount > 0) {
                TextButton(
                    onClick = { reasonsExpanded = !reasonsExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (reasonsExpanded) {
                            stringResource(R.string.plan_preview_reasons_hide)
                        } else {
                            stringResource(R.string.plan_preview_reasons_show, explainedCount)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 档案 diff：一行一项，**勾哪几项改哪几项**，点「应用」立即逐字段写、不给撤销。
 *
 * 默认一行都不勾 —— 文档"想改"不等于用户"同意改"。
 */
@Composable
private fun ProfileDiffBlock(
    rows: List<PlanPreviewViewModel.ProfileRow>,
    enabled: Boolean,
    onToggle: (Int) -> Unit,
    onApply: () -> Unit,
) {
    val checkedCount: Int = rows.count { row -> row.checked }
    Column(verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs)) {
        Text(
            text = stringResource(R.string.plan_preview_profile_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = row.checked,
                    onCheckedChange = { onToggle(index) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(profileFieldLabelRes(row.diff)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = profileFieldValueText(row.diff),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Button(onClick = onApply, enabled = enabled && checkedCount > 0) {
            val count: Int = checkedCount
            Text(text = stringResource(R.string.plan_preview_profile_apply, count))
        }
    }
}

/** 这一行改的是档案里哪一项。 */
@StringRes
private fun profileFieldLabelRes(diff: ProfileFieldDiff): Int = when (diff) {
    is ProfileFieldDiff.GoalChange -> R.string.plan_preview_field_goal
    is ProfileFieldDiff.WeightChange -> R.string.plan_preview_field_goal_weight
    is ProfileFieldDiff.DaysChange -> R.string.plan_preview_field_days
    is ProfileFieldDiff.EquipmentChange -> R.string.plan_preview_field_equipment
    is ProfileFieldDiff.InjuryAreaChange -> R.string.plan_preview_field_injuries
    is ProfileFieldDiff.InjuryNoteChange -> R.string.plan_preview_field_injury_note
}

/**
 * 「旧值 → 新值」。
 *
 * 枚举的中文说法全部复用 `ProfileSummaryCard` 里那份唯一映射 —— 档案词汇不该在这一页再念出
 * 第二套叫法（那正是以前"两份派生漂出不同结果"的成因）。
 * 空集合与空备注念「（不设）」，让用户看得出**这是要清空**，而不是"这里没写东西"。
 */
@Composable
private fun profileFieldValueText(diff: ProfileFieldDiff): String {
    val emptyText: String = stringResource(R.string.plan_preview_profile_empty)
    val arrow: String = " → "
    return when (diff) {
        is ProfileFieldDiff.GoalChange -> {
            val from: String = stringResource(goalLabelRes(diff.from))
            val to: String = stringResource(goalLabelRes(diff.to))
            from + arrow + to
        }

        is ProfileFieldDiff.WeightChange -> {
            val from: String = diff.from?.let { kilogramText(it) } ?: emptyText
            kilogramText(diff.to).let { to -> from + arrow + to }
        }

        is ProfileFieldDiff.DaysChange -> {
            val from: String = stringResource(R.string.plan_preview_profile_days_value, diff.from)
            val to: String = stringResource(R.string.plan_preview_profile_days_value, diff.to)
            from + arrow + to
        }

        is ProfileFieldDiff.EquipmentChange -> {
            val from: String = equipmentListText(diff.from, emptyText)
            val to: String = equipmentListText(diff.to, emptyText)
            from + arrow + to
        }

        is ProfileFieldDiff.InjuryAreaChange -> {
            val from: String = injuryListText(diff.from, emptyText)
            val to: String = injuryListText(diff.to, emptyText)
            from + arrow + to
        }

        is ProfileFieldDiff.InjuryNoteChange -> {
            val from: String = diff.from?.takeIf { it.isNotBlank() } ?: emptyText
            val to: String = diff.to.takeIf { it.isNotBlank() } ?: emptyText
            from + arrow + to
        }
    }
}

@Composable
private fun equipmentListText(values: Set<Equipment>, emptyText: String): String =
    if (values.isEmpty()) {
        emptyText
    } else {
        joinLabels(
            resIds = values.sortedBy { it.ordinal }.map { equipmentLabelRes(it) },
            separator = SUMMARY_SEPARATOR,
        )
    }

@Composable
private fun injuryListText(values: Set<InjuryArea>, emptyText: String): String =
    if (values.isEmpty()) {
        emptyText
    } else {
        joinLabels(
            resIds = values.sortedBy { it.ordinal }.map { injuryLabelRes(it) },
            separator = INJURY_SEPARATOR,
        )
    }

/** 整数就不带小数点（"80 kg" 比 "80.0 kg" 像人话），有小数则原样。 */
private fun kilogramText(kg: Float): String =
    if (kg % 1f == 0f) "${kg.toInt()} kg" else "$kg kg"
