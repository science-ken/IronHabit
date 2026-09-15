package com.ironhabit.app.ui.screens.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.ExerciseSuggestion
import com.ironhabit.app.domain.model.PlanNote
import com.ironhabit.app.domain.model.PlanNoteDetail
import com.ironhabit.app.domain.model.PlanReason
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.SuggestionReason
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.ui.components.AppSnackbarHost
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.ProfileSummaryCard

/**
 * Tab「AI 教练」页面（**本地规则版 · 完全离线**）。
 *
 * 4 个区块：
 * ① 我的身体档案（复用 [ProfileSummaryCard]，点击去设置页编辑）
 * ② 生成计划（写入本周计划；**用户手改行永不覆盖**）
 * ③ 教练解读（含"自由问答需要联网"的诚实禁用说明）
 * ④ 补充动作（按档案与伤病筛出，一键收入动作库，幂等）
 *
 * ⚠️ **诚实原则（硬要求）**：页首必须挂「本地规则版 · 完全离线」徽标；
 * 页内**禁止**出现"模型 / 智能生成 / AI 分析"等暗示联网或推理的措辞 —— **宁可朴素，勿误导**。
 *
 * @param onEditProfile 编辑完整档案（跳设置页「我的档案」区块）
 */
@Composable
fun AiCoachScreen(
    onEditProfile: () -> Unit,
    onAddPlan: (Int) -> Unit = {},
    onEditPlan: (Long, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: AiCoachViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 从设置页改完 Key 回来时刷新徽标（加密文件无响应式流，只能主动拉）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshKeyStatus()
    }

    val snackbarRes = uiState.snackbarRes
    if (snackbarRes != null) {
        val message = stringResource(snackbarRes, *uiState.snackbarArgs.toTypedArray())
        LaunchedEffect(snackbarRes, message) {
            snackbarHostState.showSnackbar(message)
            viewModel.onSnackbarShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { AppSnackbarHost(state = snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LocalRulesBadge(
                aiRemoteEnabled = uiState.aiRemoteEnabled,
                hasApiKey = uiState.hasApiKey,
                onGoSettings = onEditProfile,
            )

            when {
                uiState.isLoading -> {
                    LoadingSkeleton()
                    LoadingSkeleton()
                }

                else -> {
                    ProfileBlock(
                        uiState = uiState,
                        onEditProfile = onEditProfile,
                    )
                    GeneratePlanBlock(
                        uiState = uiState,
                        onGenerate = viewModel::generatePlan,
                        onAddPlan = onAddPlan,
                        onEditPlan = onEditPlan,
                    )
                    ExplainBlock(bmr = viewModel.estimateBmr())
                    SuggestBlock(
                        uiState = uiState,
                        onAdopt = viewModel::adopt,
                    )                }
            }
        }
    }
}

/** ① 我的身体档案。 */
@Composable
private fun ProfileBlock(
    uiState: AiCoachUiState,
    onEditProfile: () -> Unit,
) {
    SectionTitle(text = stringResource(R.string.ai_section_profile))
    ProfileSummaryCard(
        profile = uiState.profile,
        onClick = onEditProfile,
    )
    TextButton(onClick = onEditProfile) {
        Text(text = stringResource(R.string.ai_action_edit_profile))
    }
}

/** ② 生成计划。 */
@Composable
private fun GeneratePlanBlock(
    uiState: AiCoachUiState,
    onGenerate: () -> Unit,
    onAddPlan: (Int) -> Unit,
    onEditPlan: (Long, Int) -> Unit,
) {
    SectionTitle(text = stringResource(R.string.ai_section_generate))
    Text(
        text = stringResource(R.string.ai_generate_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onGenerate,
        enabled = !uiState.isGenerating,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val label = if (uiState.planResult == null) {
            stringResource(R.string.ai_generate_plan)
        } else {
            stringResource(R.string.ai_regenerate_plan)
        }
        Text(text = label)
    }

    val result = uiState.planResult
    if (result != null) {
        if (result.preservedCount > 0) {
            Text(
                text = stringResource(R.string.ai_plan_preserved_hint, result.preservedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.ai_plan_written_hint, result.writtenCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SourceLine(source = result.source, fallback = result.fallbackReason)

        // 本次生成的训练计划：逐条卡片（星期 / 动作 / 组数×次数×重量 / 理由），可编辑。
        if (result.plans.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.ai_plan_cards_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onAddPlan(1) }) {
                    Text(text = stringResource(R.string.action_add))
                }
            }
            val notesByExercise = remember(result.notes) {
                result.notes.associateBy { it.exerciseId }
            }
            result.plans
                .sortedWith(compareBy({ it.dayOfWeek }, { it.sortOrder }, { it.exerciseId }))
                .forEach { plan ->
                    val name = uiState.exerciseNames[plan.exerciseId].orEmpty()
                    val reason = notesByExercise[plan.exerciseId]?.let { planReasonText(it) }.orEmpty()
                    GeneratedPlanCard(
                        plan = plan,
                        exerciseName = name,
                        reason = reason,
                        onEdit = { onEditPlan(plan.id, plan.dayOfWeek) },
                    )
                }
        }

        if (result.notes.isNotEmpty()) {
            AiOutputCard(
                notes = result.notes,
                exerciseNames = uiState.exerciseNames,
                source = result.source,
            )
        }
    }
}

/**
 * 「本次生成的训练计划」中的单条动作卡。
 *
 * 把规则的产出**可视化成真计划**：星期 + 动作名 + 目标(组数×次数×重量) + 挑选理由，
 * 右上角可点「编辑」直接进计划编辑页改组数/重量/动作。解决"面板单调、看不出 AI 排了什么"。
 */
@Composable
private fun GeneratedPlanCard(
    plan: WeekPlan,
    exerciseName: String,
    reason: String,
    onEdit: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = weekdayLabel(plan.dayOfWeek),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = exerciseName.ifEmpty { stringResource(R.string.unknown_exercise) },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    if (plan.isUserEdited) {
                        Text(
                            text = stringResource(R.string.label_user_edited),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.action_edit_plan),
                    )
                }
            }
            Text(
                text = planGoalText(plan.targetSets, plan.targetReps, plan.targetWeightKg, plan.targetDurationMin),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (reason.isNotEmpty()) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 星期 → 全名标签（1=周一 … 7=周日）。 */
@Composable
private fun weekdayLabel(day: Int): String = stringResource(
    when (day) {
        1 -> R.string.weekday_mon
        2 -> R.string.weekday_tue
        3 -> R.string.weekday_wed
        4 -> R.string.weekday_thu
        5 -> R.string.weekday_fri
        6 -> R.string.weekday_sat
        else -> R.string.weekday_sun
    },
)

/** 目标文案：N组 × M次 · 重量kg（有氧则为约D分钟）。需在组合内调用（用 stringResource）。 */
@Composable
private fun planGoalText(
    sets: Int,
    reps: Int,
    weightKg: Float?,
    durationMin: Int?,
): String = buildString {
    append(stringResource(R.string.plan_goal_format, sets, reps))
    when {
        weightKg != null -> append(stringResource(R.string.plan_goal_weight, formatKg(weightKg)))
        durationMin != null -> append(stringResource(R.string.plan_goal_duration, durationMin))
    }
}

/**
 * 「本次挑了这些动作」——把规则/AI 给出的每一条选择**连同理由**摊开给用户看。
 *
 * 只报"写了几条"看不出差别，这里逐条列出「动作名 · 为什么选它」：
 * 主项 / 辅助 / 按器械 / 避伤病 / 加重 / 维持。来源标签一并显示（AI 还是本地规则）。
 */
@Composable
private fun AiOutputCard(
    notes: List<PlanNote>,
    exerciseNames: Map<Long, String>,
    source: AdviceSource,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.ai_output_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (source == AdviceSource.REMOTE_LLM) {
                            R.string.ai_source_remote
                        } else {
                            R.string.ai_source_local
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            notes.forEach { note ->
                val name = exerciseNames[note.exerciseId] ?: return@forEach
                Text(
                    text = "$name · ${planReasonText(note)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** [PlanNote] → 理由文案。按明细类型选句子：重量变化说 kg、组数变化说组、无参数说"维持原目标"。 */
@Composable
private fun planReasonText(note: PlanNote): String = when (note.kind) {
    PlanReason.PRIMARY_LIFT -> stringResource(R.string.reason_primary_lift)
    PlanReason.SUPPLEMENT -> stringResource(R.string.reason_supplement)
    PlanReason.EQUIPMENT_MATCHED -> stringResource(R.string.reason_equipment_matched)
    PlanReason.INJURY_SAFE -> stringResource(R.string.reason_injury_safe)
    PlanReason.PROGRESSIVE_OVERLOAD -> when (val detail = note.detail) {
        is PlanNoteDetail.SetsDelta ->
            stringResource(R.string.reason_progressive_overload_sets, detail.newSets.toString())
        else -> stringResource(
            R.string.reason_progressive_overload,
            formatWeight(detail),
        )
    }

    PlanReason.MAINTAIN -> when (note.detail) {
        is PlanNoteDetail.WeightDelta ->
            stringResource(R.string.reason_maintain, formatWeight(note.detail))
        else -> stringResource(R.string.reason_maintain_plain)
    }
}

/** 从 [PlanNoteDetail] 取"新重量/新组数"作为展示参数。 */
private fun formatWeight(detail: PlanNoteDetail): String = when (detail) {
    is PlanNoteDetail.WeightDelta -> detail.newWeightKg?.let { formatKg(it) } ?: ""
    is PlanNoteDetail.SetsDelta -> "${detail.newSets}"
    PlanNoteDetail.None -> ""
}

/** 整数重量不带小数点（避免显示成 `20.0`）。 */
private fun formatKg(kg: Float): String {
    val rounded = kotlin.math.round(kg * 10) / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}

/** ③ 教练解读（含自由问答的诚实禁用说明）。 */
@Composable
private fun ExplainBlock(bmr: Int?) {
    SectionTitle(text = stringResource(R.string.ai_section_explain))
    Card(
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
                text = stringResource(R.string.ai_freechat_disabled),
                style = MaterialTheme.typography.bodySmall,
            )
            if (bmr != null) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.ai_explain_bmr, bmr),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.ai_safety_note),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** ④ 补充动作 · 一键收入。 */
@Composable
private fun SuggestBlock(
    uiState: AiCoachUiState,
    onAdopt: (String) -> Unit,
) {
    SectionTitle(text = stringResource(R.string.ai_section_suggest))
    Text(
        text = stringResource(R.string.ai_suggest_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SourceLine(source = uiState.suggestionSource, fallback = uiState.suggestionFallbackReason)

    if (uiState.suggestions.isEmpty()) {
        EmptyState(text = stringResource(R.string.ai_suggest_all_adopted))
        return
    }

    uiState.suggestions.forEach { suggestion ->
        SuggestionRow(
            suggestion = suggestion,
            adopted = suggestion.name in uiState.adoptedNames,
            onAdopt = { onAdopt(suggestion.name) },
        )
        HorizontalDivider()
    }
}

@Composable
private fun SuggestionRow(
    suggestion: ExerciseSuggestion,
    adopted: Boolean,
    onAdopt: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(suggestionReasonRes(suggestion.reason)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAdopt, enabled = !adopted) {
            Text(text = stringResource(R.string.ai_suggest_adopt))
        }
    }
}

/**
 * 来源诚实标注：本次结果到底是谁产的。
 *
 * 三态：联网失败回落本地 > AI 联网生成 > 本地规则。**不许 UI 猜、不许美化**——
 * 这是你自己的原则「离线仍可用，AI 只是增强」落到界面上的部分。
 */
@Composable
private fun SourceLine(
    source: AdviceSource,
    fallback: RemoteFallbackReason?,
) {
    val text = when {
        fallback != null -> stringResource(R.string.ai_source_fallback)
        source == AdviceSource.REMOTE_LLM -> stringResource(R.string.ai_source_remote)
        else -> stringResource(R.string.ai_source_local)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 页首徽标：三态诚实标注当前 AI 模式，让用户一眼看出背后是谁在干活。 */
@Composable
private fun LocalRulesBadge(
    aiRemoteEnabled: Boolean,
    hasApiKey: Boolean,
    onGoSettings: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(badgeRes(aiRemoteEnabled, hasApiKey)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (!aiRemoteEnabled || !hasApiKey) {
                TextButton(onClick = onGoSettings) {
                    Text(text = stringResource(R.string.ai_action_go_settings))
                }
            }
        }
    }
}

private fun badgeRes(aiRemoteEnabled: Boolean, hasApiKey: Boolean): Int = when {
    aiRemoteEnabled && hasApiKey -> R.string.ai_badge_remote_on
    aiRemoteEnabled -> R.string.ai_badge_remote_no_key
    else -> R.string.ai_badge_local
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 补充动作理由 → 文案资源（规则层只产枚举，文案一律走资源）。 */
private fun suggestionReasonRes(reason: SuggestionReason): Int = when (reason) {
    SuggestionReason.INJURY_SWAP -> R.string.note_ai_injury_swap
    SuggestionReason.EQUIPMENT_FIT -> R.string.note_ai_equipment_fit
    SuggestionReason.GOAL_SUPPORT -> R.string.note_ai_goal_support
}
