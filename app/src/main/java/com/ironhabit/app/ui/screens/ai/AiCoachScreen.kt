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
 * Tab銆孉I 鏁欑粌銆嶉〉闈紙**鑱旂綉鍙€?路 鏈湴瑙勫垯鍏滃簳**锛夈€?
 *
 * 鍖哄潡锛?
 * 鈶?鎴戠殑韬綋妗ｆ锛堝鐢?[ProfileSummaryCard]锛岀偣鍑诲幓璁剧疆椤电紪杈戯級
 * 鈶?鐢熸垚璁″垝锛堝啓鍏ユ湰鍛ㄨ鍒掞紱**鐢ㄦ埛鎵嬫敼琛屾案涓嶈鐩?*锛?
 * 鈶 楗璁″垝锛堟暟鍊煎叏閮ㄦ湰鍦扮畻锛岃仈缃戞椂鍙﹂檮銆屼负浠€涔堣繖鏍峰悆銆嶏級
 * 鈶 杩涘害瑙ｈ锛堟暟瀛楁潵鑷湰鍦拌仛鍚堬紝鑱旂綉鏃跺彟闄?AI 鐨勪笅涓€姝ュ缓璁級
 * 鈶?鏁欑粌瑙ｈ锛圔MR 绛夋湰鍦拌В璇伙紱鏈仈缃戞椂闄勫甫鑷敱闂瓟鐨勮瘹瀹炵鐢ㄨ鏄庯級
 * 鈶?琛ュ厖鍔ㄤ綔锛堟寜妗ｆ涓庝激鐥呯瓫鍑猴紝涓€閿敹鍏ュ姩浣滃簱锛屽箓绛夛級
 * 鈶?闂暀缁冿紙AI 鑷敱闂瓟锛岃仈缃戝彲閫夛級
 *
 * 鈿狅笍 **璇氬疄鍘熷垯锛堢‖瑕佹眰锛?*锛?
 * 1. 椤甸寰芥爣濡傚疄鍙嶆槧涓夋€侊紙鏈湴瑙勫垯 / 鑱旂綉宸插紑 / 鏈厤 Key锛夛紱
 * 2. **鍙湁鐪熺殑璋冪敤浜?DeepSeek** 鎵嶆樉绀恒€孉I 鍒嗘瀽銆嶅瓧鏍凤紝鏉ユ簮鐢?UseCase 鍥炰紶銆乁I 涓嶇寽锛?
 * 3. 绂荤嚎 / 鏃?Key / 璋冪敤澶辫触涓€寰嬪洖钀芥湰鍦拌鍒欏苟**鏄庣‘鏍囨敞**锛岀粷涓嶅啋鍏?AI銆?
 *
 * @param onEditProfile 缂栬緫瀹屾暣妗ｆ锛堣烦璁剧疆椤点€屾垜鐨勬。妗堛€嶅尯鍧楋級
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

    // 銆孉I 浼氱湅鍒颁粈涔堛€嶆暟鎹寘寮瑰眰锛堝彧鍦ㄧ敤鎴风偣浜嗗鍑哄悗鍑虹幇锛夈€?
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

            // 澶辫触蹇呴』鍙锛氱敓鎴?/ 寤鸿鍔犺浇 / 鏀跺叆澶辫触閮藉湪杩欓噷缁欏唴鑱旈敊璇崱 + 涓€閿噸璇曪紝
            // 鑰屼笉鏄儚浠ュ墠閭ｆ牱"浠€涔堥兘娌″彂鐢?銆傛斁鍦?when 涔嬪锛屽姞杞戒腑涔熺収鏍疯兘鐪嬪埌閿欒銆?
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
                    // 鈶燘 鍛ㄥ鐩?+ 鏁版嵁鍖咃紙P2锛夛細鏁板瓧鍏ㄩ儴鏈湴绠楋紝鏁版嵁鍖呭彲澶嶅埗缁欎换浣?AI銆?
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
                    // 鈶 楗璁″垝锛堝瓙椤?B锛夛細鏈湴绠楁暟鍊?+ 鑱旂綉鏃堕檮 AI 鐨勩€屼负浠€涔堣繖鏍峰悆銆嶃€?
                    DietBlock(
                        uiState = uiState,
                        onGenerateDiet = viewModel::generateDiet,
                    )
                    // 鈶 杩涘害瑙ｈ锛堝瓙椤?C锛夛細鏁板瓧鏉ヨ嚜鏈湴鑱氬悎锛岃仈缃戞椂闄?AI 鐨勪笅涓€姝ュ缓璁€?
                    InsightBlock(
                        uiState = uiState,
                        onReload = viewModel::loadInsight,
                    )
                    ExplainBlock(bmr = viewModel.estimateBmr(), canAsk = uiState.canAskCoach)
                    // 鈶?闂暀缁冿紙AI 鑷敱闂瓟锛夛細绂荤嚎鏄剧ず璇氬疄绂佺敤璇存槑锛屽湪绾垮彲鐢紝鍙戦€佷腑绂佺敤銆?
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

/** 鈶?鐢熸垚璁″垝銆?*/
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

        // 鏈鐢熸垚鐨勮缁冭鍒掞細閫愭潯鍗＄墖锛堟槦鏈?/ 鍔ㄤ綔 / 缁勬暟脳娆℃暟脳閲嶉噺 / 鐞嗙敱锛夛紝鍙紪杈戙€?
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
 * 銆屾湰娆＄敓鎴愮殑璁粌璁″垝銆嶄腑鐨勫崟鏉″姩浣滃崱銆?
 *
 * 鎶婅鍒欑殑浜у嚭**鍙鍖栨垚鐪熻鍒?*锛氭槦鏈?+ 鍔ㄤ綔鍚?+ 鐩爣(缁勬暟脳娆℃暟脳閲嶉噺) + 鎸戦€夌悊鐢憋紝
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

/** 鏄熸湡 鈫?鍏ㄥ悕鏍囩锛?=鍛ㄤ竴 鈥?7=鍛ㄦ棩锛夈€?*/
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
 * 銆屾湰娆℃寫浜嗚繖浜涘姩浣溿€嶁€斺€旀妸瑙勫垯/AI 缁欏嚭鐨勬瘡涓€鏉￠€夋嫨**杩炲悓鐞嗙敱**鎽婂紑缁欑敤鎴风湅銆?
 *
 * 鍙姤"鍐欎簡鍑犳潯"鐪嬩笉鍑哄樊鍒紝杩欓噷閫愭潯鍒楀嚭銆屽姩浣滃悕 路 涓轰粈涔堥€夊畠銆嶏細
 * 涓婚」 / 杈呭姪 / 鎸夊櫒姊?/ 閬夸激鐥?/ 鍔犻噸 / 缁存寔銆傛潵婧愭爣绛句竴骞舵樉绀猴紙AI 杩樻槸鏈湴瑙勫垯锛夈€?
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

/** 杩滅 AI锛圖eepSeek锛夎繑鍥炵殑鑷敱鏂囨湰鍒嗘瀽锛屼粎褰?source==REMOTE_LLM 涓旀湁鍐呭鏃舵樉绀恒€?*/
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

/** 鏈湴瑙勫垯鐨勩€岀敓鎴愪緷鎹€嶏細鎶婅鍒欑湡姝ｇ敤鍒扮殑妗ｆ杈撳叆閫愭潯鎽婂紑锛屾槑纭爣娉ㄩ潪 AI 鑱旂綉鐢熸垚锛堣瘹瀹炲師鍒欙級銆?*/
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

/** [PlanBasisItem.key]锛坰trings.xml 璧勬簮鍚嶏級鈫?璧勬簮 id銆?*/
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

/** [PlanNote] 鈫?鐞嗙敱鏂囨銆傛寜鏄庣粏绫诲瀷閫夊彞瀛愶細閲嶉噺鍙樺寲璇?kg銆佺粍鏁板彉鍖栬缁勩€佹棤鍙傛暟璇?缁存寔鍘熺洰鏍?銆?*/
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

/** 浠?[PlanNoteDetail] 鍙?鏂伴噸閲?鏂扮粍鏁?浣滀负灞曠ず鍙傛暟銆?*/
private fun formatWeight(detail: PlanNoteDetail): String = when (detail) {
    is PlanNoteDetail.WeightDelta -> detail.newWeightKg?.let { formatKg(it) } ?: ""
    is PlanNoteDetail.SetsDelta -> "${detail.newSets}"
    PlanNoteDetail.None -> ""
}

/** 鏁存暟閲嶉噺涓嶅甫灏忔暟鐐癸紙閬垮厤鏄剧ず鎴?`20.0`锛夈€?*/
internal fun formatKg(kg: Float): String {
    val rounded = kotlin.math.round(kg * 10) / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}

/**
 * 鈶 楗璁″垝锛堝瓙椤?B锛夈€?
 *
 * **鏁板€间竴寰嬫潵鑷湰鍦扮函鍑芥暟**锛堢儹閲?/ 铔嬬櫧璐?/ 鍚勯鍐呭锛夛紝杩滅 AI 鍙彁渚涗竴娈?
 * 銆屼负浠€涔堣繖鏍峰悆銆嶇殑鏂囧瓧鍒嗘瀽 鈥斺€?鍥犳绂荤嚎銆佹湭閰?Key銆佽皟鐢ㄥけ璐ユ椂杩欓噷渚濈劧鏈夊畬鏁村彲淇＄殑缁撴灉锛?
 * 鍙槸鎶婂垎鏋愬崱鎹㈡垚**鏄庣‘鏍囨敞鏈湴瑙勫垯**鐨勩€岀敓鎴愪緷鎹€嶅崱銆?
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
                // 鈿狅笍 msg_diet_filtered 鐨勫崰浣嶇鏄?%1$s锛圫tring 閫氶亾锛夆啋 浼?toString()锛屽埆浼?Int銆?
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

/** 杩滅 AI 鐨勩€屼负浠€涔堣繖鏍峰悆銆嶏紙浠呰仈缃戞垚鍔熸椂鍑虹幇锛涙暟鍊间粛浠ユ湰鍦颁负鍑嗭級銆?*/
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

/** 绂荤嚎 / 鏈仈缃戞椂鐨勬湰鍦般€岀敓鎴愪緷鎹€嶅崱锛?*鏄庣‘鏍囨敞鏈湴瑙勫垯**锛屼笉鍐掑厖 AI锛夈€?*/
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
 * 鈶 杩涘害瑙ｈ锛堝瓙椤?C锛夈€?
 *
 * **鏁板瓧姘歌繙鏉ヨ嚜鏈湴鑱氬悎**锛堟墦鍗℃鏁?/ 骞冲潎 RPE / 浣撻噸鍙樺寲 / 杩炵画澶╂暟锛夛細
 * 鑱旂綉鎴愬姛鏃跺涓€娈?AI 鏂囨锛堟爣棰樻爣娉ㄣ€孉I 鍒嗘瀽銆嶏級锛屽惁鍒欐樉绀烘湰鍦板皬缁撳苟鏍囨敞銆屾湰鍦拌鍒欍€嶁€斺€?
 * 涓よ€呴兘涓嶄細缂烘暟瀛楋紝鎵€浠ョ绾夸篃鑳界湅銆?
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

/** 骞冲潎 RPE 灞曠ず锛氬洓鑸嶄簲鍏ュ埌涓€浣嶅皬鏁帮紱鏃犺褰曟樉绀恒€屾殏鏃犮€嶏紙鏂囨鍦?strings.xml锛夈€?*/
@Composable
private fun insightRpeText(rpe: Double?): String {
    if (rpe == null) return stringResource(R.string.ai_insight_unknown)
    val rounded: Double = kotlin.math.round(rpe * 10.0) / 10.0
    return rounded.toString()
}

/** 浣撻噸鍙樺寲灞曠ず锛氬甫绗﹀彿涓€浣嶅皬鏁帮紙濡?`+0.4` / `-0.6`锛夛紱涓嶈冻涓ゆ潯璁板綍鏄剧ず銆屾殏鏃犮€嶃€?*/
@Composable
private fun insightWeightDeltaText(deltaKg: Float?): String {
    if (deltaKg == null) return stringResource(R.string.ai_insight_unknown)
    val rounded: Float = kotlin.math.round(deltaKg * 10f) / 10f
    val sign: String = if (rounded > 0f) "+" else ""
    return "$sign$rounded"
}

/**
 * 鈶?鏁欑粌瑙ｈ锛堝惈鑷敱闂瓟鐨勮瘹瀹炶鏄庯級銆?
 *
 * @param canAsk 銆岄棶鏁欑粌銆嶆槸鍚﹀叿澶囪仈缃戞潯浠讹紙寮€鍏冲紑 + 宸查厤 Key锛夈€?
 *   `false` 鈫?淇濈暀鍘熸湁鐨勮瘹瀹炵鐢ㄨ鏄?[R.string.ai_freechat_disabled]锛?
 *   `true` 鈫?**涓嶅啀鏄剧ず閭ｅ彞璇?*锛堜笅鏂广€岄棶鏁欑粌銆嶅尯鍧楀凡缁忓彲鐢紝缁х画璇?鏆備笉鎻愪緵"灏辨槸涓嶈瘹瀹烇級銆?
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
 * 鏉ユ簮璇氬疄鏍囨敞锛氭湰娆＄粨鏋滃埌搴曟槸璋佷骇鐨勩€?
 *
 * 涓夋€侊細鑱旂綉澶辫触鍥炶惤鏈湴 > AI 鑱旂綉鐢熸垚 > 鏈湴瑙勫垯銆?*涓嶈 UI 鐚溿€佷笉璁哥編鍖?*鈥斺€?
 * 杩欐槸浣犺嚜宸辩殑鍘熷垯銆岀绾夸粛鍙敤锛孉I 鍙槸澧炲己銆嶈惤鍒扮晫闈笂鐨勯儴鍒嗐€?
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

/** 椤甸寰芥爣锛氫笁鎬佽瘹瀹炴爣娉ㄥ綋鍓?AI 妯″紡锛岃鐢ㄦ埛涓€鐪肩湅鍑鸿儗鍚庢槸璋佸湪骞叉椿銆?*/
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
 * 鍐呰仈閿欒鍗★細鎶娿€岀敓鎴?/ 寤鸿鍔犺浇 / 鏀跺叆銆嶇殑澶辫触鎽嗗埌椤甸潰涓婏紝骞剁粰涓€涓?*鐪熸鏈夌敤**鐨勯噸璇曟寜閽€?
 *
 * 閰嶈壊鐢?`errorContainer` 涓庢甯稿崱鐗囧尯鍒嗭紱鎸夐挳鏂囨璧?[R.string.action_retry]锛堜笉鍦ㄧ姸鎬侀噷瀛樺瓧绗︿覆锛夈€?
 *
 * @param message 宸茬敱璋冪敤鏂?`stringResource` 瑙ｆ瀽濂界殑閿欒鏂囨
 * @param onRetry 閲嶈瘯鍥炶皟锛圴iewModel 浼氶噸璺戝け璐ョ殑閭ｄ釜鍔ㄤ綔锛?
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

/** 琛ュ厖鍔ㄤ綔鐞嗙敱 鈫?鏂囨璧勬簮锛堣鍒欏眰鍙骇鏋氫妇锛屾枃妗堜竴寰嬭蛋璧勬簮锛夈€?*/
private fun suggestionReasonRes(reason: SuggestionReason): Int = when (reason) {
    SuggestionReason.INJURY_SWAP -> R.string.note_ai_injury_swap
    SuggestionReason.EQUIPMENT_FIT -> R.string.note_ai_equipment_fit
    SuggestionReason.GOAL_SUPPORT -> R.string.note_ai_goal_support
}
