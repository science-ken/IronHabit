package com.ironhabit.app.ui.screens.ai

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.ExerciseSuggestion
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.SuggestionReason
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.ui.components.AppSnackbarHost
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.ProfileSummaryCard
import com.ironhabit.app.ui.components.equipmentLabelRes
import com.ironhabit.app.ui.components.joinLabels
import com.ironhabit.app.ui.theme.IronHabitShapes
import com.ironhabit.app.ui.theme.IronHabitSpacing
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 *
 *
 *
 */
@Composable
fun AiCoachScreen(
    onEditProfile: () -> Unit,
    /** 生成完跳到「本周计划预览」页，由用户逐天采纳（AI 页自己不再写库）。 */
    onOpenPlanPreview: () -> Unit = {},
    /** 跳「训练 → 动作库」：补充动作建议已经搬去那一屏，这里只留入口。 */
    onOpenExerciseLibrary: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AiCoachViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(AiCoachTab.REVIEW) }

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

    // `LaunchedEffect` 无条件创建：放在 `if` 里会让它随分支进出被启停，而 `showSnackbar` 是挂起的 ——
    // 显示期间 `snackbarRes` 一变，协程就被取消，提示闪一下没了（连续触发两条时尤其明显）。
    // 与 `BackupScreen` / `TodayScreen` / `SettingsScreen` 同一写法。
    val snackbarMessage: String? = uiState.snackbarRes?.let { res ->
        stringResource(res, *uiState.snackbarArgs.toTypedArray())
    }
    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(snackbarMessage)
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

            AiCoachTabBar(
                selected = tab,
                onSelect = { tab = it },
            )

            when {
                uiState.isLoading -> {
                    LoadingSkeleton()
                    LoadingSkeleton()
                }

                tab == AiCoachTab.REVIEW -> WeeklyReviewBlock(
                    uiState = uiState,
                    onWeekChange = viewModel::loadWeeklyReview,
                    onExport = viewModel::onExportPackage,
                    onReloadInsight = viewModel::loadInsight,
                    // 点 chip = 跳到「问教练」并把带数字的完整问题填进输入框。
                    // 刻意不直接发出去：用户可能想改两个字再问，替他发就收不回来了。
                    onAskAbout = { question ->
                        tab = AiCoachTab.ASK
                        viewModel.onChatInputChange(question)
                    },
                )

                else -> {
                    ProfileBlock(
                        uiState = uiState,
                        onEditProfile = onEditProfile,
                    )
                    CoachChatCard(
                        canAsk = uiState.canAskCoach,
                        messages = uiState.chatMessages,
                        input = uiState.chatInput,
                        isAsking = uiState.isAsking,
                        onInputChange = viewModel::onChatInputChange,
                        onSend = viewModel::onAskCoach,
                    )
                    AiCoachToolRow(
                        uiState = uiState,
                        onGeneratePlan = viewModel::generatePlan,
                        onGenerateDiet = viewModel::generateDiet,
                        onOpenExerciseLibrary = onOpenExerciseLibrary,
                    )
                    DietResults(uiState = uiState)
                }
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
    ProfileFactsLine(profile = uiState.profile)
    TextButton(onClick = onEditProfile) {
        Text(text = stringResource(R.string.ai_action_edit_profile))
    }
}

/**
 * 档案卡下面那行「怎么练」：器械 + 每周天数。
 *
 * 放在卡片**外面**是刻意的 —— [com.ironhabit.app.ui.components.ProfileSummaryCard] 由「我的」页
 * 共用，它的注释禁止为单个页面加分支参数。
 *
 * 每周天数恒有值（默认 3），所以这行总会渲染；器械一项没勾就只留天数。
 */
@Composable
private fun ProfileFactsLine(profile: UserProfile) {
    val parts: List<String> = buildList {
        if (profile.equipment.isNotEmpty()) {
            add(
                stringResource(
                    R.string.ai_profile_equipment,
                    joinLabels(
                        resIds = profile.equipment.sortedBy { it.ordinal }.map { equipmentLabelRes(it) },
                        separator = EQUIPMENT_SEPARATOR,
                    ),
                ),
            )
        }
        add(stringResource(R.string.ai_profile_weekly_days, profile.trainingDaysPerWeek))
    }
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val EQUIPMENT_SEPARATOR = " / "

// `formatKg`（算出来的浮点：先四舍五入到 1 位再去 `.0` 尾）住在 `ui/Format.kt`，
// 与设置页那条 `toDisplayNumber` 并排，两条为什么不能合并写在那边文件头。

/**
 *
 */
@Composable
private fun DietResults(
    uiState: AiCoachUiState,
) {
    // 按钮已经收进 AiCoachToolRow；这里只剩"生成出来的是什么"。
    // 没生成过（dietSummary == null）时整块不渲染，不留一个空标题。
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
internal fun SourceLine(
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

/**
 * 顶部两段：把「打开就有、数字全靠本地算」和「每问一次都要联网」分开。
 *
 * ⚠️ 小字刻意**没有**照抄设计稿的「本地 0 token」：教练解读已经并进复盘屏的周卡底部，
 * 那一段文字是联网要来的。标成「0 token」就是谎报，所以改成只声明数字的口径。
 */
private enum class AiCoachTab(@StringRes val labelRes: Int, @StringRes val noteRes: Int) {
    REVIEW(R.string.ai_tab_review, R.string.ai_tab_review_note),
    ASK(R.string.ai_tab_ask, R.string.ai_tab_ask_note),
}

@Composable
private fun AiCoachTabBar(
    selected: AiCoachTab,
    onSelect: (AiCoachTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(IronHabitShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(IronHabitSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
    ) {
        AiCoachTab.entries.forEach { tab ->
            val isSelected: Boolean = tab == selected
            val contentColor = if (isSelected) {
                MaterialTheme.colorScheme.inverseOnSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(IronHabitShapes.small)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.inverseSurface else Color.Transparent,
                    )
                    .clickable { onSelect(tab) }
                    .padding(vertical = IronHabitSpacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                )
                Text(
                    text = stringResource(tab.noteRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = TabNoteAlpha),
                )
            }
        }
    }
}

/** 选中态的小字压在深色底上，靠降透明度处理，不再新引一个颜色 token。 */
private const val TabNoteAlpha: Float = 0.72f


/**
 * 问教练屏底部的三枚工具：生成训练计划 / 分析饮食 / 动作库。
 *
 * 原来这两个生成动作各自是一个完整区块（标题 + 说明 + 整宽按钮），
 * 拆两段式之后它们和对话挤在同一屏里，三枚并排才看得完"这一屏能干什么"。
 *
 * ⚠️ 「动作库」只是**跳过去**，不在本页给建议 —— 点「收入」会隐藏地再花一次
 * completion（`SuggestExercisesUseCase.adopt` 要重新问一次顾问确认候选），
 * 所以建议整块搬进了动作库那一屏，这里只留入口。
 */
@Composable
private fun AiCoachToolRow(
    uiState: AiCoachUiState,
    onGeneratePlan: () -> Unit,
    onGenerateDiet: () -> Unit,
    onOpenExerciseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        ToolButton(
            labelRes = if (uiState.isGenerating) {
                R.string.ai_tool_plan_busy
            } else {
                R.string.ai_tool_plan
            },
            noteRes = R.string.ai_tool_plan_note,
            enabled = !uiState.isGenerating,
            modifier = Modifier.weight(1f),
            onClick = onGeneratePlan,
        )
        ToolButton(
            labelRes = if (uiState.dietSummary == null) {
                R.string.ai_tool_diet
            } else {
                R.string.ai_tool_diet_again
            },
            noteRes = R.string.ai_tool_diet_note,
            enabled = !uiState.isGeneratingDiet,
            modifier = Modifier.weight(1f),
            onClick = onGenerateDiet,
        )
        ToolButton(
            labelRes = R.string.ai_tool_library,
            noteRes = R.string.ai_tool_library_note,
            enabled = true,
            modifier = Modifier.weight(1f),
            onClick = onOpenExerciseLibrary,
        )
    }
}

@Composable
private fun ToolButton(
    @StringRes labelRes: Int,
    @StringRes noteRes: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        // M3 默认是胶囊形：三枚并排时被撑成三个圆、中文还折成三行。
        // 原型是方角小块，所以显式给卡片圆角 + 收紧内边距。
        shape = IronHabitShapes.card,
        contentPadding = PaddingValues(
            horizontal = IronHabitSpacing.sm,
            vertical = IronHabitSpacing.sm,
        ),
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                text = stringResource(noteRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

