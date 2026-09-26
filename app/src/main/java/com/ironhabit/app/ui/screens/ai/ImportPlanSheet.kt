package com.ironhabit.app.ui.screens.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.ironhabit.app.R
import com.ironhabit.app.domain.ai.external.ExternalPlanDocumentParser
import com.ironhabit.app.domain.ai.external.ExternalPlanNote
import com.ironhabit.app.domain.ai.external.ImportSection
import com.ironhabit.app.domain.ai.external.NewExerciseCandidate
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.ui.components.mealTypeLabelRes
import com.ironhabit.app.ui.components.weekRangeText
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 「导入外部 AI 的计划」弹层：模板出去、文档回来，两头都在这一屏。
 *
 * 抄的是 `WeekPackageSheet` 的两条真机教训（不是风格问题，是踩过的坑）：
 * 1. **反馈画在弹层里**：Snackbar 属于外层 Scaffold，会被底部弹层盖住 → 点了像没反应；
 * 2. **整列必须可滚 + 有高度上限**：小屏上长文本会把按钮顶出屏幕。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportPlanSheet(
    uiState: ExternalImportViewModel.UiState,
    thisWeekStartEpochDay: Long,
    nextWeekStartEpochDay: Long,
    onWeekChange: (ExternalImportViewModel.WeekChoice) -> Unit,
    onCopyTemplate: () -> Unit,
    onTemplateCopied: () -> Unit,
    onTextChange: (String) -> Unit,
    onReadClipboard: (String?) -> Unit,
    onParse: () -> Unit,
    onToggleNewExercise: (Int) -> Unit,
    onCreateSelected: () -> Unit,
    onProceedWithoutThem: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = IronHabitSpacing.xl)
                .padding(bottom = IronHabitSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
        ) {
            Text(
                text = stringResource(
                    if (uiState.mode == ImportSection.DIET) {
                        R.string.ai_import_title_diet
                    } else {
                        R.string.ai_import_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.ai_import_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 周落点放在最前面：模板里"这一周已经排了什么"那一段是按它拼的，先选后复制才不用返工。
            Text(
                text = stringResource(R.string.ai_import_week_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm)) {
                val thisWeek = ExternalImportViewModel.WeekChoice.THIS_WEEK
                FilterChip(
                    selected = uiState.week == thisWeek,
                    onClick = { onWeekChange(thisWeek) },
                    label = {
                        val range = weekRangeText(thisWeekStartEpochDay)
                        Text(text = stringResource(R.string.ai_import_week_this, range))
                    },
                )
                val nextWeek = ExternalImportViewModel.WeekChoice.NEXT_WEEK
                FilterChip(
                    selected = uiState.week == nextWeek,
                    onClick = { onWeekChange(nextWeek) },
                    label = {
                        val range = weekRangeText(nextWeekStartEpochDay)
                        Text(text = stringResource(R.string.ai_import_week_next, range))
                    },
                )
            }

            Text(
                text = stringResource(R.string.ai_import_step_template),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 反馈压在按钮上方：弹层底边已经贴着屏幕下沿，写在下面永远出不了屏。
            if (copied) {
                Text(
                    text = stringResource(R.string.ai_import_template_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            uiState.templateFailedRes?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    val template: String? = uiState.template
                    if (template != null) {
                        clipboard.setText(AnnotatedString(template))
                        copied = true
                        onTemplateCopied()
                    } else {
                        // 还没拼过就先拼一次，用户不必退出再来。
                        onCopyTemplate()
                    }
                },
                // 复盘还在算（首次进页那一瞬）时没数据可拼，此刻禁用并说明原因。
                enabled = !uiState.isBuildingTemplate,
            ) {
                Text(
                    text = stringResource(
                        if (uiState.isBuildingTemplate) R.string.ai_import_template_building
                        else R.string.ai_import_template_copy,
                    ),
                )
            }

            Text(
                text = stringResource(R.string.ai_import_step_paste),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uiState.text,
                onValueChange = onTextChange,
                label = { Text(text = stringResource(R.string.ai_import_paste_hint)) },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { onReadClipboard(clipboard.getText()?.text) }) {
                Text(text = stringResource(R.string.ai_import_read_clipboard))
            }

            uiState.refusalRes?.let { refusalRes ->
                // 拒收必须给"下一步做什么"，光说"格式错误"等于把问题原样退回给用户。
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(refusalRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(IronHabitSpacing.md),
                    )
                }
                ImportPlanNoteList(notes = uiState.notes)
                // 模型自己写的原因原样贴出来（它是自由文本，不进 String.format，也不参与任何数字）。
                uiState.refusalAnalysis?.let { said ->
                    Text(
                        text = stringResource(R.string.ai_import_ai_said),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = said,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 库里没有、但文档声明过的动作：勾选才建。建动作是往用户库里**永久加一行**，
            // 一个错别字（"杠铃深蹲 "）就该被这一勾挡住，所以不给默认全选。
            if (uiState.newExercises.isNotEmpty()) {
                NewExerciseBlock(
                    rows = uiState.newExercises,
                    enabled = !uiState.isCreating,
                    onToggle = onToggleNewExercise,
                    onCreate = onCreateSelected,
                )
                // 退路：不想建这些动作时也得能走。没有这一条，"有候选"就把用户锁在弹层里了。
                if (uiState.canProceedWithoutThem) {
                    TextButton(
                        onClick = onProceedWithoutThem,
                        enabled = !uiState.isCreating,
                    ) {
                        Text(text = stringResource(R.string.ai_import_proceed_without))
                    }
                }
            }

            Button(
                onClick = onParse,
                enabled = !uiState.isParsing && uiState.text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (uiState.isParsing) R.string.ai_import_parsing else R.string.ai_import_parse,
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 待确认的新动作清单。
 *
 * 只念名字 + 肌群：肌群本身就是中文数据（`MuscleGroup` 词表），而分类的中文映射现在
 * 在 4 个文件里各有一份私有副本，这里不再加第 5 份 —— 用户真要核对分类，
 * 建完在「训练 → 动作库」那条详情页看得到、也改得动。
 */
@Composable
private fun NewExerciseBlock(
    rows: List<NewExerciseCandidate>,
    enabled: Boolean,
    onToggle: (Int) -> Unit,
    onCreate: () -> Unit,
) {
    val checkedCount: Int = rows.count { row -> row.checked }
    Column(verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs)) {
        Text(
            text = stringResource(R.string.ai_import_new_exercise_title),
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
                        text = row.exercise.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = row.exercise.muscleGroups.joinToString("/").ifEmpty {
                            stringResource(R.string.ai_import_new_exercise_no_muscle)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Button(onClick = onCreate, enabled = enabled && checkedCount > 0) {
            val count: Int = checkedCount
            Text(text = stringResource(R.string.ai_import_new_exercise_create, count))
        }
    }
}

/**
 * 逐条"什么没进来 / 什么被动过"。
 *
 * 这张清单是这条路的全部诚实所在：外部 AI 的动作名对不对得上，用户**在手机上永远看不见**，
 * 不列出来他就只会发现"我这周怎么少了两条"。
 */
@Composable
internal fun ImportPlanNoteList(notes: List<ExternalPlanNote>) {
    if (notes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs)) {
        Text(
            text = stringResource(R.string.plan_preview_import_notes_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 挡过忌口就必须跟这一句：不写它，这份清单会被读成"已按忌口过滤干净了"，
        // 而实际上只有食物库里**标了类别**的那几条会被挡（库里多数条目没有标注）。
        if (notes.any { note -> note.kind == ExternalPlanNote.Kind.FOOD_RESTRICTED }) {
            Text(
                text = stringResource(R.string.ai_import_restriction_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        notes.forEach { note -> ImportPlanNoteLine(note) }
    }
}

/**
 * 一条说明。
 *
 * ⚠️ 实参一律 `toString()` 成 String 再传：这几个资源全部用 `%1$s` / `%2$s`。
 * 走 `%d` 的话，清单里的数字一旦哪天变成模型给的字符串，就是运行时崩在这里。
 */
@Composable
private fun ImportPlanNoteLine(note: ExternalPlanNote) {
    val subject: String = note.subject.orEmpty()
    val firstArg: String = note.args.firstOrNull()?.toString().orEmpty()
    val dailyLimit: String = ExternalPlanDocumentParser.MAX_ITEMS_PER_DAY.toString()
    val mealLimit: String = ExternalPlanDocumentParser.MAX_FOODS_PER_MEAL.toString()
    // 餐次的中文唯一真源是 `strings.xml` 里的 `meal_*`，note 只带枚举名（架构 §7.5 禁止硬编码中文）。
    val mealLabel: String = MealType.entries.firstOrNull { type -> type.name == subject }
        ?.let { type -> stringResource(mealTypeLabelRes(type)) }
        ?: subject
    // ⚠️ 下面每一句取文案的调用都必须**整句写在一行**（含全部实参）：
    // `StringResourcePlaceholderContractTest` 的调用点扫描按行匹配，
    // 把资源名换行写就会读成"实参个数为 0"，带参文案当场红灯。
    val text: String = when (note.kind) {
        ExternalPlanNote.Kind.EXERCISE_INACTIVE -> stringResource(R.string.plan_preview_note_exercise_inactive, subject)
        ExternalPlanNote.Kind.EXERCISE_CREATABLE -> stringResource(R.string.plan_preview_note_exercise_creatable, subject)
        ExternalPlanNote.Kind.NEW_EXERCISE_REJECTED -> stringResource(R.string.plan_preview_note_new_exercise_rejected, subject)
        ExternalPlanNote.Kind.NEW_EXERCISE_CATEGORY_DEFAULTED -> stringResource(R.string.plan_preview_note_exercise_category_defaulted, subject, firstArg)
        ExternalPlanNote.Kind.NEW_EXERCISE_MUSCLES_DROPPED -> stringResource(R.string.plan_preview_note_exercise_muscles_dropped, subject, firstArg)
        ExternalPlanNote.Kind.BLANK_EXERCISE_NAME -> stringResource(R.string.plan_preview_note_blank_name)
        ExternalPlanNote.Kind.DUPLICATE_EXERCISE -> stringResource(R.string.plan_preview_note_duplicate, subject)
        ExternalPlanNote.Kind.OVER_DAILY_LIMIT -> stringResource(R.string.plan_preview_note_over_limit, subject, dailyLimit)
        ExternalPlanNote.Kind.SETS_CLAMPED -> stringResource(R.string.plan_preview_note_sets_clamped, subject, firstArg)
        ExternalPlanNote.Kind.REPS_CLAMPED -> stringResource(R.string.plan_preview_note_reps_clamped, subject, firstArg)
        ExternalPlanNote.Kind.PROFILE_FIELD_FORBIDDEN -> stringResource(R.string.plan_preview_note_profile_forbidden, subject)
        ExternalPlanNote.Kind.PROFILE_VALUE_REJECTED -> stringResource(R.string.plan_preview_note_profile_value_rejected, subject)
        ExternalPlanNote.Kind.FOOD_CREATABLE -> stringResource(R.string.plan_preview_note_food_creatable, subject)
        ExternalPlanNote.Kind.FOOD_INACTIVE -> stringResource(R.string.plan_preview_note_food_inactive, subject)
        ExternalPlanNote.Kind.FOOD_RESTRICTED -> stringResource(R.string.plan_preview_note_food_restricted, subject)
        ExternalPlanNote.Kind.BLANK_FOOD_NAME -> stringResource(R.string.plan_preview_note_blank_food_name)
        ExternalPlanNote.Kind.DUPLICATE_FOOD -> stringResource(R.string.plan_preview_note_duplicate_food, subject)
        ExternalPlanNote.Kind.OVER_MEAL_LIMIT -> stringResource(R.string.plan_preview_note_over_meal_limit, subject, mealLimit)
        ExternalPlanNote.Kind.GRAMS_CLAMPED -> stringResource(R.string.plan_preview_note_grams_clamped, subject, firstArg)
        ExternalPlanNote.Kind.MEAL_TYPE_UNKNOWN -> stringResource(R.string.plan_preview_note_meal_type_unknown, subject)
        ExternalPlanNote.Kind.DUPLICATE_MEAL -> stringResource(R.string.plan_preview_note_duplicate_meal, mealLabel)
        ExternalPlanNote.Kind.NEW_FOOD_VALUE_REJECTED -> stringResource(R.string.plan_preview_note_new_food_value_rejected, subject)
        ExternalPlanNote.Kind.MEAL_ENTRIES_MISSING -> stringResource(R.string.plan_preview_note_meal_entries_missing, firstArg)
        ExternalPlanNote.Kind.DIET_SECTION_MISPLACED -> stringResource(R.string.plan_preview_note_diet_section_misplaced, subject)
    }
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}
