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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.ExerciseSuggestion
import com.ironhabit.app.domain.model.PlanNote
import com.ironhabit.app.domain.model.PlanNoteDetail
import com.ironhabit.app.domain.model.PlanReason
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.SuggestionReason
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
    modifier: Modifier = Modifier,
    viewModel: AiCoachViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
            LocalRulesBadge()

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
        if (result.notes.isNotEmpty()) {
            ReasonList(
                notes = result.notes,
                exerciseNames = uiState.exerciseNames,
            )
        }
    }
}

/**
 * 「为什么这样排」列表：把规则层给出的 [PlanNote] 渲染成"动作名 · 理由"。
 *
 * 规则层只产枚举与结构化参数，文案一律在此处经 `strings.xml` 映射（禁止硬编码中文）。
 */
@Composable
private fun ReasonList(
    notes: List<PlanNote>,
    exerciseNames: Map<Long, String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        notes.forEach { note ->
            val name = exerciseNames[note.exerciseId] ?: return@forEach
            Text(
                text = "$name · ${planReasonText(note)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** [PlanNote] → 理由文案（含重量/组数参数）。 */
@Composable
private fun planReasonText(note: PlanNote): String = when (note.kind) {
    PlanReason.PRIMARY_LIFT -> stringResource(R.string.reason_primary_lift)
    PlanReason.SUPPLEMENT -> stringResource(R.string.reason_supplement)
    PlanReason.EQUIPMENT_MATCHED -> stringResource(R.string.reason_equipment_matched)
    PlanReason.INJURY_SAFE -> stringResource(R.string.reason_injury_safe)
    PlanReason.PROGRESSIVE_OVERLOAD -> stringResource(
        R.string.reason_progressive_overload,
        formatWeight(note.detail),
    )

    PlanReason.MAINTAIN -> stringResource(R.string.reason_maintain, formatWeight(note.detail))
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

/** 页首「本地规则版」徽标：让用户一眼看出这不是大模型。 */
@Composable
private fun LocalRulesBadge() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text(
            text = stringResource(R.string.ai_local_badge),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
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
