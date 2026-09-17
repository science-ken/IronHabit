package com.ironhabit.app.ui.screens.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.ReviewNote
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.ui.theme.IronHabitSpacing
import com.ironhabit.app.ui.theme.IronHabitTypeStyles
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

/**
 * 「周复盘」区块（P2）：这一周**实际**练了什么 —— 数据全部本地算（[WeeklyReview]），不联网、不花 token。
 *
 * 与方案（定稿预览稿）的对应关系：
 * - 出勤 / 总容量 / 平均 RPE / 体重变化 四个数字来自 [com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase]；
 * - 「进步 / 停滞」按周对周比较得出（停滞 = 连续 3 周有记录但没涨）；
 * - 数据不足的地方**说清楚为什么**（[ReviewNote]），不显示 0 冒充数字；
 * - 往期可以回看（`‹ 上一周`），往期是**只读**的 —— 改历史数据没有意义，也容易改坏。
 *
 * 无状态组件：不持有 ViewModel，回调交给 `AiCoachViewModel`。
 *
 * @param uiState 页面状态（只读，用于取 [AiCoachUiState.weeklyReview] / 加载标志 / 周偏移）
 * @param onWeekChange 切到某一周（`0` = 本周，`-1` = 上一周）
 * @param onExport 生成数据包并打开弹层
 */
@Composable
internal fun WeeklyReviewBlock(
    uiState: AiCoachUiState,
    onWeekChange: (Int) -> Unit,
    onExport: () -> Unit,
) {
    SectionTitle(text = stringResource(R.string.ai_review_title))
    Text(
        text = stringResource(R.string.ai_review_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = { onWeekChange(uiState.weekOffset - 1) }) {
            Text(text = stringResource(R.string.ai_review_nav_prev))
        }
        TextButton(
            onClick = { onWeekChange(uiState.weekOffset + 1) },
            // 本周是"最新"的一周：不许再往未来翻（未来的周复盘没有意义）。
            enabled = uiState.weekOffset < 0,
        ) {
            Text(text = stringResource(R.string.ai_review_nav_next))
        }
    }

    val review: WeeklyReview? = uiState.weeklyReview
    when {
        review == null && uiState.isLoadingReview -> {
            Text(
                text = stringResource(R.string.ai_review_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        review == null -> {
            Text(
                text = stringResource(R.string.ai_review_note_no_checkin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> WeeklyReviewCard(review = review, weekOffset = uiState.weekOffset, onExport = onExport)
    }
}

/** 周复盘卡片本体（数字 + 趋势 + 诚实说明 + 导出入口）。 */
@Composable
private fun WeeklyReviewCard(
    review: WeeklyReview,
    weekOffset: Int,
    onExport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(IronHabitSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            Text(
                text = weekLabel(weekOffset) + " · " + weekRange(review),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            StatRow(
                leftLabel = stringResource(R.string.ai_review_stat_days),
                leftValue = "${review.training.completedDays} / ${review.training.plannedDays}",
                rightLabel = stringResource(R.string.ai_review_stat_volume),
                rightValue = formatKg(review.training.totalVolumeKg),
            )
            StatRow(
                leftLabel = stringResource(R.string.ai_review_stat_rpe),
                leftValue = review.training.avgRpe?.let { formatKg(it) }
                    ?: stringResource(R.string.ai_review_value_missing),
                rightLabel = stringResource(R.string.ai_review_stat_weight),
                rightValue = review.body.deltaKg?.let { delta ->
                    (if (delta > 0f) "+" else "") + formatKg(delta)
                } ?: stringResource(R.string.ai_review_value_missing),
            )
            StatRow(
                leftLabel = stringResource(R.string.ai_review_stat_diet),
                leftValue = review.diet.avgKcal?.toString()
                    ?: stringResource(R.string.ai_review_value_missing),
                rightLabel = null,
                rightValue = null,
            )

            trendLines(review)?.let { lines ->
                Text(
                    text = lines,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // 数据不足的诚实说明：一条一行，顺序由 UseCase 固定。
            review.notes.forEach { note ->
                Text(
                    text = stringResource(noteRes(note)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.ai_review_export_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.ai_review_export))
            }
        }
    }
}

/** 「AI 会看到什么」弹层：把数据包 JSON 原样摊开给用户看（可复制）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeekPackageSheet(
    json: String?,
    includeDetails: Boolean,
    onToggleDetails: () -> Unit,
    onCopied: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val clipboard = LocalClipboardManager.current

    // ⚠️ 复制反馈必须**画在弹层里**：Snackbar 属于外层 Scaffold，会**被底部弹层盖住**
    //（真机上就是这么发现的：点了「复制 JSON」毫无反馈，用户会以为按钮坏了）。
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(json) { copied = false }

    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(
            modifier = Modifier
                // ⚠️ 必须可滚动 + 有高度上限：小屏（1080×1920）上长 JSON 会把按钮顶出屏幕
                //（「编辑一餐」弹层踩过同一个坑，见 `MealEditSheet`）。
                .verticalScroll(rememberScrollState())
                .padding(horizontal = IronHabitSpacing.xl)
                .padding(bottom = IronHabitSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
        ) {
            Text(
                text = stringResource(R.string.ai_package_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.ai_review_export_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FilterChip(
                selected = includeDetails,
                onClick = { onToggleDetails() },
                label = {
                    Text(
                        text = stringResource(
                            if (includeDetails) {
                                R.string.ai_package_detail_on
                            } else {
                                R.string.ai_package_detail_off
                            },
                        ),
                    )
                },
            )

            val text: String = json ?: stringResource(R.string.ai_package_building)
            if (json == null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                ) {
                    Text(
                        text = text,
                        style = IronHabitTypeStyles.code,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(IronHabitSpacing.md),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
            ) {
                Button(
                    onClick = {
                        if (json != null) {
                            clipboard.setText(AnnotatedString(json))
                            copied = true
                            onCopied()
                        }
                    },
                    enabled = json != null,
                ) {
                    Text(text = stringResource(R.string.ai_package_copy))
                }
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(R.string.ai_package_close))
                }
            }

            if (copied) {
                Text(
                    text = stringResource(R.string.ai_package_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 两列统计行（右列给 `null` 就只显示左列）。 */
@Composable
private fun StatRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String?,
    rightValue: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
    ) {
        StatCell(label = leftLabel, value = leftValue, modifier = Modifier.weight(1f))
        if (rightLabel != null && rightValue != null) {
            StatCell(label = rightLabel, value = rightValue, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = IronHabitSpacing.sm, horizontal = IronHabitSpacing.md),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 进步 / 停滞两行；两者都为空 → 返回 `null`（UI 显示"还看不出来"）。 */
@Composable
private fun trendLines(review: WeeklyReview): String? {
    val progressed: String = review.training.progressed.joinToString("、") { trend ->
        trend.exerciseName
    }
    // ⚠️ 必须用 `map`（inline 函数，允许在里面调 `stringResource`）再 `joinToString`；
    // 直接在 `joinToString` 的 lambda 里调 `stringResource` 会编译不过（非 inline 参数不是 @Composable 上下文）。
    val stalled: String = review.training.stalled
        .map { trend ->
            stringResource(R.string.ai_review_stalled_item, trend.exerciseName, trend.stagnantWeeks)
        }
        .joinToString("、")

    val lines: List<String> = buildList {
        if (progressed.isNotEmpty()) {
            add(stringResource(R.string.ai_review_progressed, progressed))
        }
        if (stalled.isNotEmpty()) {
            add(stringResource(R.string.ai_review_stalled, stalled))
        }
    }
    return when {
        lines.isNotEmpty() -> lines.joinToString("\n")
        review.training.completedDays > 0 -> stringResource(R.string.ai_review_trend_none)
        else -> null
    }
}

/** 周标题（`本周` / `上一周` / `N 周前`）。 */
@Composable
private fun weekLabel(weekOffset: Int): String = when (weekOffset) {
    0 -> stringResource(R.string.ai_review_week_this)
    -1 -> stringResource(R.string.ai_review_week_prev)
    else -> stringResource(R.string.ai_review_week_offset, -weekOffset)
}

/** 日期区间（`9/14 ~ 9/20`）。 */
@Composable
private fun weekRange(review: WeeklyReview): String = stringResource(
    R.string.ai_review_week_range,
    monthDay(review.weekStartEpochDay),
    monthDay(review.weekEndEpochDay),
)

/** epochDay → `M/D`（只用于标签，不带年份）。 */
private fun monthDay(epochDay: Long): String {
    val date: LocalDate = LocalDate.fromEpochDays(epochDay.toInt())
    return "${date.monthNumber}/${date.dayOfMonth}"
}

/** [ReviewNote] → 资源 id（**不含中文**，文案全在 `strings.xml`）。 */
private fun noteRes(note: ReviewNote): Int = when (note) {
    ReviewNote.NO_CHECKIN -> R.string.ai_review_note_no_checkin
    ReviewNote.NO_RPE -> R.string.ai_review_note_no_rpe
    ReviewNote.NO_WEIGHT -> R.string.ai_review_note_no_weight
    ReviewNote.NO_DIET -> R.string.ai_review_note_no_diet
    ReviewNote.WEEK_IN_PROGRESS -> R.string.ai_review_note_in_progress
}
