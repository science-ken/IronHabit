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
import com.ironhabit.app.domain.model.PlanBasisItem
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
import com.ironhabit.app.ui.components.aiPlanGoalText
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 *
 *
 *
 */
@Composable
fun AiCoachScreen(
    onEditProfile: () -> Unit,
    onAddPlan: (Int) -> Unit = {},
    onEditPlan: (Long, Int) -> Unit = { _, _ -> },
    /** 生成完跳到「本周计划预览」页，由用户逐天采纳（AI 页自己不再写库）。 */
    onOpenPlanPreview: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AiCoachViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.previewRequested) {
        if (uiState.previewRequested) {
            viewModel.onPreviewConsumed()
            onOpenPlanPreview()
        }
    }

    // 浠庤缃〉鏀瑰畬 Key 鍥炴潵鏃跺埛鏂板窘鏍囷紙鍔犲瘑鏂囦欢鏃犲搷搴斿紡娴侊紝鍙兘涓诲姩鎷夛級銆?
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

    if (uiState.weekPackageJson != null || uiState.isBuildingPackage) {
        WeekPackageSheet(
            json = uiState.weekPackageJson,
            includeDetails = uiState.includePackageDetails,
            onToggleDetails = viewModel::onTogglePackageDetails,
            onCopied = viewModel::onPackageCopied,
            onDismissRequest = viewModel::onDismissPackage,
        )
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
                .padding(IronHabitSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.lg),
        ) {
            LocalRulesBadge(
                aiRemoteEnabled = uiState.aiRemoteEnabled,
                hasApiKey = uiState.hasApiKey,
                onGoSettings = onEditProfile,
            )

            val errorRes: Int? = uiState.errorRes
            if (errorRes != null) {
                ErrorCard(
                    message = stringResource(errorRes),
                    onRetry = viewModel::onRetry,
                )
            }

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
                    WeeklyReviewBlock(
                        uiState = uiState,
                        onWeekChange = viewModel::loadWeeklyReview,
                        onExport = viewModel::onExportPackage,
                    )
                    GeneratePlanBlock(
                        uiState = uiState,
                        onGenerate = viewModel::generatePlan,
                        onAddPlan = onAddPlan,
                        onEditPlan = onEditPlan,
                    )
                    DietBlock(
                        uiState = uiState,
                        onGenerateDiet = viewModel::generateDiet,
                    )
                    InsightBlock(
                        uiState = uiState,
                        onReload = viewModel::loadInsight,
                    )
                    ExplainBlock(bmr = viewModel.estimateBmr(), canAsk = uiState.canAskCoach)
                    CoachChatCard(
                        canAsk = uiState.canAskCoach,
                        messages = uiState.chatMessages,
                        input = uiState.chatInput,
                        isAsking = uiState.isAsking,
                        onInputChange = viewModel::onChatInputChange,
                        onSend = viewModel::onAskCoach,
                    )
                    SuggestBlock(
                        uiState = uiState,
                        onAdopt = viewModel::adopt,
                    )                }
            }
        }
    }
}

/** 鈶?鎴戠殑韬綋妗ｆ銆?*/
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
        if (result.retiredCount > 0) {
            Text(
                text = stringResource(R.string.ai_plan_retired_hint, result.retiredCount),
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

        when (result.source) {
            AdviceSource.REMOTE_LLM -> {
                if (!result.analysis.isNullOrBlank()) {
                    AiAnalysisCard(analysis = result.analysis)
                }
            }
            else -> {
                if (result.basis.isNotEmpty()) {
                    LocalBasisCard(basis = result.basis)
                }
            }
        }
    }
}

/**
 *
 * 鍙充笂瑙掑彲鐐广€岀紪杈戙€嶇洿鎺ヨ繘璁″垝缂栬緫椤垫敼缁勬暟/閲嶉噺/鍔ㄤ綔銆傝В鍐?闈㈡澘鍗曡皟銆佺湅涓嶅嚭 AI 鎺掍簡浠€涔?銆?
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
                .padding(horizontal = IronHabitSpacing.lg, vertical = IronHabitSpacing.md),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
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
                        modifier = Modifier.padding(start = IronHabitSpacing.sm),
                    )
                    if (plan.isUserEdited) {
                        Text(
                            text = stringResource(R.string.label_user_edited),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = IronHabitSpacing.sm),
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
                text = aiPlanGoalText(plan.targetSets, plan.targetReps, plan.targetWeightKg, plan.targetDurationMin),
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

/**
 *
 * 鍙姤"鍐欎簡鍑犳潯"鐪嬩笉鍑哄樊鍒紝杩欓噷閫愭潯鍒楀嚭銆屽姩浣滃悕 路 涓轰粈涔堥€夊畠銆嶏細
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
                .padding(horizontal = IronHabitSpacing.lg, vertical = IronHabitSpacing.md),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
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
                    text = "$name 路 ${planReasonText(note)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AiAnalysisCard(analysis: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = IronHabitSpacing.lg, vertical = IronHabitSpacing.md),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.ai_analysis_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = analysis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun LocalBasisCard(basis: List<PlanBasisItem>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = IronHabitSpacing.lg, vertical = IronHabitSpacing.md),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.ai_basis_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.ai_basis_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            basis.forEach { item ->
                val res = basisKeyRes(item.key)
                Text(
                    text = if (item.args.isEmpty()) {
                        stringResource(res)
                    } else {
                        stringResource(res, *item.args.toTypedArray())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun basisKeyRes(key: String): Int = when (key) {
    "basis_frequency" -> R.string.basis_frequency
    "basis_goal" -> R.string.basis_goal
    "basis_volume" -> R.string.basis_volume
    "basis_cardio" -> R.string.basis_cardio
    "basis_profile" -> R.string.basis_profile
    "basis_recovery_age" -> R.string.basis_recovery_age
    "basis_age_volume" -> R.string.basis_age_volume
    "basis_bodyfat_high" -> R.string.basis_bodyfat_high
    "basis_bodyfat_low" -> R.string.basis_bodyfat_low
    "basis_weight_cut" -> R.string.basis_weight_cut
    "basis_weight_gain" -> R.string.basis_weight_gain
    "basis_injury" -> R.string.basis_injury
    "basis_injury_swap" -> R.string.basis_injury_swap
    "basis_equipment" -> R.string.basis_equipment
    "basis_library_too_narrow" -> R.string.basis_library_too_narrow
    "basis_overload" -> R.string.basis_overload
    "basis_history_none" -> R.string.basis_history_none
    else -> R.string.basis_frequency
}

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

private fun formatWeight(detail: PlanNoteDetail): String = when (detail) {
    is PlanNoteDetail.WeightDelta -> detail.newWeightKg?.let { formatKg(it) } ?: ""
    is PlanNoteDetail.SetsDelta -> "${detail.newSets}"
    PlanNoteDetail.None -> ""
}

internal fun formatKg(kg: Float): String {
    val rounded = kotlin.math.round(kg * 10) / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}

/**
 *
 */
@Composable
private fun DietBlock(
    uiState: AiCoachUiState,
    onGenerateDiet: () -> Unit,
) {
    SectionTitle(text = stringResource(R.string.ai_section_diet))
    Text(
        text = stringResource(R.string.ai_diet_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onGenerateDiet,
        enabled = !uiState.isGeneratingDiet,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val label = if (uiState.dietSummary == null) {
            stringResource(R.string.ai_generate_diet)
        } else {
            stringResource(R.string.ai_regenerate_diet)
        }
        Text(text = label)
    }

    val summary = uiState.dietSummary
    if (summary != null) {
        if (summary.preservedCount > 0) {
            Text(
                text = stringResource(R.string.ai_diet_preserved_hint, summary.preservedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(
                R.string.ai_explain_intake,
                summary.targetKcal,
                summary.targetProtein,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (summary.usedDefaults) {
            Text(
                text = stringResource(R.string.ai_diet_used_defaults),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (summary.filteredCount > 0) {
            Text(
                text = stringResource(R.string.msg_diet_filtered, summary.filteredCount.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val analysis = uiState.dietAnalysis
        if (!analysis.isNullOrBlank()) {
            DietAnalysisCard(analysis = analysis)
        } else {
            DietLocalBasisCard()
        }
    }
}

@Composable
private fun DietAnalysisCard(analysis: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IronHabitSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.ai_diet_analysis_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = analysis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DietLocalBasisCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IronHabitSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.ai_diet_basis_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.ai_diet_basis_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 *
 */
@Composable
private fun InsightBlock(
    uiState: AiCoachUiState,
    onReload: () -> Unit,
) {
    SectionTitle(text = stringResource(R.string.ai_insight_section))
    Text(
        text = stringResource(R.string.ai_insight_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val insight = uiState.insightResult
    when {
        insight == null && uiState.isLoadingInsight -> LoadingSkeleton()

        insight == null -> Text(
            text = stringResource(R.string.ai_insight_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> {
            val context = insight.context
            val fromAi: Boolean = insight.source == AdviceSource.REMOTE_LLM
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (fromAi) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (fromAi) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(IronHabitSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
                ) {
                    Text(
                        text = stringResource(
                            if (fromAi) R.string.ai_insight_remote_title else R.string.ai_insight_local_title,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (context.checkInCount > 0) {
                        Text(
                            text = stringResource(
                                R.string.ai_insight_stats,
                                context.checkInCount,
                                insightRpeText(context.averageRpe),
                                insightWeightDeltaText(context.weightDeltaKg),
                                context.currentStreak,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.ai_insight_empty),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    val analysis = insight.text
                    if (fromAi && !analysis.isNullOrBlank()) {
                        Text(text = analysis, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            text = stringResource(R.string.ai_insight_local_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (insight.fallbackReason == RemoteFallbackReason.REMOTE_ERROR) {
                            Text(
                                text = stringResource(R.string.ai_insight_fallback),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    TextButton(onClick = onReload) {
        Text(text = stringResource(R.string.ai_insight_reload))
    }
}

@Composable
private fun insightRpeText(rpe: Double?): String {
    if (rpe == null) return stringResource(R.string.ai_insight_unknown)
    val rounded: Double = kotlin.math.round(rpe * 10.0) / 10.0
    return rounded.toString()
}

@Composable
private fun insightWeightDeltaText(deltaKg: Float?): String {
    if (deltaKg == null) return stringResource(R.string.ai_insight_unknown)
    val rounded: Float = kotlin.math.round(deltaKg * 10f) / 10f
    val sign: String = if (rounded > 0f) "+" else ""
    return "$sign$rounded"
}

/**
 *
 */
@Composable
private fun ExplainBlock(bmr: Int?, canAsk: Boolean) {
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
                .padding(horizontal = IronHabitSpacing.lg, vertical = IronHabitSpacing.md),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            if (!canAsk) {
                Text(
                    text = stringResource(R.string.ai_freechat_disabled),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (bmr != null) {
                if (!canAsk) HorizontalDivider()
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

/** 鈶?琛ュ厖鍔ㄤ綔 路 涓€閿敹鍏ャ€?*/
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
            .padding(vertical = IronHabitSpacing.md),
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
 *
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
                .padding(horizontal = IronHabitSpacing.md, vertical = IronHabitSpacing.xs),
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

/**
 *
 *
 * @param message 宸茬敱璋冪敤鏂?`stringResource` 瑙ｆ瀽濂界殑閿欒鏂囨
 */
@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = IronHabitSpacing.lg, vertical = IronHabitSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun suggestionReasonRes(reason: SuggestionReason): Int = when (reason) {
    SuggestionReason.INJURY_SWAP -> R.string.note_ai_injury_swap
    SuggestionReason.EQUIPMENT_FIT -> R.string.note_ai_equipment_fit
    SuggestionReason.GOAL_SUPPORT -> R.string.note_ai_goal_support
}
