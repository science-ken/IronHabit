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

            // 失败必须可见：生成 / 建议加载 / 收入失败都在这里给内联错误卡 + 一键重试，
            // 而不是像以前那样"什么都没发生"。放在 when 之外，加载中也照样能看到错误。
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
                    GeneratePlanBlock(
                        uiState = uiState,
                        onGenerate = viewModel::generatePlan,
                        onAddPlan = onAddPlan,
                        onEditPlan = onEditPlan,
                    )
                    // ②B 饮食计划（子项 B）：本地算数值 + 联网时附 AI 的「为什么这样吃」。
                    DietBlock(
                        uiState = uiState,
                        onGenerateDiet = viewModel::generateDiet,
                    )
                    ExplainBlock(bmr = viewModel.estimateBmr(), canAsk = uiState.canAskCoach)
                    // ⑤ 问教练（AI 自由问答）：离线显示诚实禁用说明，在线可用，发送中禁用。
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

/** 远端 AI（DeepSeek）返回的自由文本分析，仅当 source==REMOTE_LLM 且有内容时显示。 */
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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

/** 本地规则的「生成依据」：把规则真正用到的档案输入逐条摊开，明确标注非 AI 联网生成（诚实原则）。 */
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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

/** [PlanBasisItem.key]（strings.xml 资源名）→ 资源 id。 */
private fun basisKeyRes(key: String): Int = when (key) {
    "basis_frequency" -> R.string.basis_frequency
    "basis_goal" -> R.string.basis_goal
    "basis_profile" -> R.string.basis_profile
    "basis_injury" -> R.string.basis_injury
    "basis_equipment" -> R.string.basis_equipment
    "basis_overload" -> R.string.basis_overload
    "basis_history_none" -> R.string.basis_history_none
    else -> R.string.basis_frequency
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

/**
 * ②B 饮食计划（子项 B）。
 *
 * **数值一律来自本地纯函数**（热量 / 蛋白质 / 各餐内容），远端 AI 只提供一段
 * 「为什么这样吃」的文字分析 —— 因此离线、未配 Key、调用失败时这里依然有完整可信的结果，
 * 只是把分析卡换成**明确标注本地规则**的「生成依据」卡。
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
                // ⚠️ msg_diet_filtered 的占位符是 %1$s（String 通道）→ 传 toString()，别传 Int。
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

/** 远端 AI 的「为什么这样吃」（仅联网成功时出现；数值仍以本地为准）。 */
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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

/** 离线 / 未联网时的本地「生成依据」卡（**明确标注本地规则**，不冒充 AI）。 */
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
 * ③ 教练解读（含自由问答的诚实说明）。
 *
 * @param canAsk 「问教练」是否具备联网条件（开关开 + 已配 Key）。
 *   `false` → 保留原有的诚实禁用说明 [R.string.ai_freechat_disabled]；
 *   `true` → **不再显示那句话**（下方「问教练」区块已经可用，继续说"暂不提供"就是不诚实）。
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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

/**
 * 内联错误卡：把「生成 / 建议加载 / 收入」的失败摆到页面上，并给一个**真正有用**的重试按钮。
 *
 * 配色用 `errorContainer` 与正常卡片区分；按钮文案走 [R.string.action_retry]（不在状态里存字符串）。
 *
 * @param message 已由调用方 `stringResource` 解析好的错误文案
 * @param onRetry 重试回调（ViewModel 会重跑失败的那个动作）
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
