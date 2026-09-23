package com.ironhabit.app.ui.screens.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.ReviewNote
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.usecase.CoachInsightResult
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.formatKg
import com.ironhabit.app.ui.theme.IronHabitShapes
import com.ironhabit.app.ui.theme.IronHabitSpacing
import com.ironhabit.app.ui.theme.IronHabitTypeStyles
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.scale
import com.ironhabit.app.domain.model.WeekDayDetail
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.saveable.rememberSaveable

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
 * @param onReloadInsight 重新向模型要一次解读（解读现在长在**这张卡**的底部，不再单开区块）
 */
@Composable
internal fun WeeklyReviewBlock(
    uiState: AiCoachUiState,
    onWeekChange: (Int) -> Unit,
    onExport: () -> Unit,
    onReloadInsight: () -> Unit,
    onAskAbout: (String) -> Unit,
) {
    // 选中哪一格。按周翻篇重置 —— 同一个下标在另一周指的是另一天。
    var selectedDayIndex by remember(uiState.weekOffset) { mutableStateOf<Int?>(null) }
    // 默认展开（原型 reviewOpen=true）；翻周时重置，避免上周收着、回到本周还是空的
    var expanded by rememberSaveable(uiState.weekOffset) { mutableStateOf(true) }

    val review: WeeklyReview? = uiState.weeklyReview
    when {
        review == null && uiState.isLoadingReview -> {
            ReviewPending(text = stringResource(R.string.ai_review_loading))
        }

        review == null -> {
            // 没有复盘数据 = 还没练过，说的就是真实本周。
            ReviewPending(text = stringResource(R.string.ai_review_note_no_checkin, weekLabel(0)))
        }

        else -> {
            WeeklyReviewCard(
                review = review,
                weekOffset = uiState.weekOffset,
                insight = uiState.insightResult,
                isLoadingInsight = uiState.isLoadingInsight,
                onWeekChange = onWeekChange,
                onExport = onExport,
                onReloadInsight = onReloadInsight,
                selectedDayIndex = selectedDayIndex,
                onSelectDay = { index -> selectedDayIndex = index },
                expanded = expanded,
                onToggleExpanded = { expanded = !expanded },
            )

            AnomalyChips(review = review, onAsk = onAskAbout)


            val openIndex: Int? = selectedDayIndex
            val day: WeekDayDetail? = openIndex?.let { review.days.getOrNull(it) }
            if (day != null) {
                WeekDaySheet(day = day, onDismiss = { selectedDayIndex = null })
            }
        }
    }
}

/** 还没算出来 / 这周什么都没有时：只给一行说明，不开一张空卡。 */
@Composable
private fun ReviewPending(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 周复盘卡片本体（标题行 + 数字 + 趋势 + 诚实说明 + 导出入口 + 教练解读）。 */
@Composable
private fun WeeklyReviewCard(
    review: WeeklyReview,
    weekOffset: Int,
    insight: CoachInsightResult?,
    isLoadingInsight: Boolean,
    onWeekChange: (Int) -> Unit,
    onExport: () -> Unit,
    onReloadInsight: () -> Unit,
    selectedDayIndex: Int?,
    onSelectDay: (Int) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(IronHabitSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // 标题跟着周偏移走：以前翻到上周，卡里还顶着「本周复盘」四个字，
                    // 下面一行却写「上一周 · 9/14 ~ 9/20」—— 同一张卡自相矛盾。
                    Text(
                        text = weekLabel(weekOffset),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = weekRange(review),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 箭头是装饰性图标：`contentDescription = null` 让它不进无障碍树，TalkBack 只念「上一周」。
                // 以前箭头烧在字符串里（「‹ 上一周」），无障碍读出来就是一个多余的尖括号 —— B1 那条的漏网之鱼。
                TextButton(onClick = { onWeekChange(weekOffset - 1) }) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(IronHabitSpacing.xs))
                    Text(text = stringResource(R.string.ai_review_nav_prev))
                }
                TextButton(
                    onClick = { onWeekChange(weekOffset + 1) },
                    // 本周是"最新"的一周：不许再往未来翻（未来的周复盘没有意义）。
                    enabled = weekOffset < 0,
                ) {
                    Text(text = stringResource(R.string.ai_review_nav_next))
                    Spacer(modifier = Modifier.width(IronHabitSpacing.xs))
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }

            if (!expanded) {
                Text(
                    text = collapsedSummary(review),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val training = review.training
                MetricRow(
                    cells = buildList {
                        if (training.totalVolumeKg > 0f) {
                            add(MetricCell(stringResource(R.string.ai_review_stat_capacity), capacityText(training.totalVolumeKg)))
                        }
                        if (training.totalSets > 0 || training.plannedSets > 0) {
                            add(
                                MetricCell(
                                    stringResource(R.string.ai_review_stat_sets),
                                    if (training.plannedSets > 0) {
                                        stringResource(R.string.ai_review_sets_ratio, training.totalSets, training.plannedSets)
                                    } else {
                                        training.totalSets.toString()
                                    },
                                ),
                            )
                        }
                        training.avgRpe?.let { rpe ->
                            add(MetricCell(stringResource(R.string.ai_review_stat_rpe), formatKg(rpe)))
                        }
                        review.body.latestWeightKg?.let { weight ->
                            add(
                                MetricCell(
                                    stringResource(R.string.ai_review_stat_weight_now),
                                    formatKg(weight),
                                    sub = review.body.deltaKg?.let { delta -> weightDeltaText(delta) },
                                ),
                            )
                        }
                    },
                )
                MetricRow(
                    cells = buildList {
                        add(
                            MetricCell(
                                stringResource(R.string.ai_review_stat_days),
                                "${training.completedDays}/${training.plannedDays}",
                            ),
                        )
                        review.diet.avgKcal?.let { kcal ->
                            // 只要有任何一天是打勾估的，日均前面就得带「约」：只在"全是估的"时才标，
                            // 会让一周里记一天明细就把另外六天的猜测洗成准数 —— 而 AI 会照着这个数开建议。
                            // 标签两个分支都带「日均」：数字一直是日均，换成日卡的「热量 · 粗记」
                            // 就会被读成一整周只吃了这么多（旁边组数格可是周总量）。
                            val approx: Boolean = review.diet.preciseDays < review.diet.loggedDays
                            add(
                                MetricCell(
                                    stringResource(
                                        if (approx) R.string.ai_review_stat_diet_approx else R.string.ai_review_stat_diet,
                                    ),
                                    if (approx) stringResource(R.string.ai_review_diet_approx, kcal) else kcal.toString(),
                                ),
                            )
                        }
                        review.diet.avgProteinG?.let { protein ->
                            add(MetricCell(stringResource(R.string.ai_review_stat_protein), protein.toString()))
                        }
                        if (review.body.sampleCount > 0) {
                            add(
                                MetricCell(
                                    stringResource(R.string.ai_review_stat_weighins),
                                    stringResource(R.string.ai_review_unit_weighins, review.body.sampleCount),
                                ),
                            )
                        }
                    },
                )

                WeekHeatStrip(
                    days = review.days,
                    todayEpochDay = review.todayEpochDay,
                    selected = selectedDayIndex,
                    onSelect = onSelectDay,
                )
                Text(
                    text = stringResource(R.string.ai_review_heat_basis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                trendLines(review, weekLabel(weekOffset))?.let { lines ->
                    Text(
                        text = lines,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 数据不足的诚实说明：一条一行，顺序由 UseCase 固定。
                review.notes.forEach { note ->
                    Text(
                        text = noteText(note, weekLabel(weekOffset)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            }

            // 导出与展开/收起在 if/else 之外：折叠态若只剩一行摘要、没有出口，
            // 用户就再也打不开了（原型同样把这两个按钮放在正文之外）。
            Text(
                text = stringResource(R.string.ai_review_export_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
            ) {
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.ai_review_export))
                }
                TextButton(onClick = onToggleExpanded) {
                    Text(
                        text = stringResource(
                            if (expanded) R.string.ai_review_collapse else R.string.ai_review_expand,
                        ),
                    )
                }
            }

            InsightSection(
                insight = insight,
                isLoading = isLoadingInsight,
                weekOffset = weekOffset,
                onReload = onReloadInsight,
            )
        }
    }
}

/**
 * 卡片底部的「教练解读」。
 *
 * ⚠️ 回看旧周时**不给解读、只说明为什么不给**：解读要的是「最近两周」这个窗口，
 * 翻到上周还挂着这段文字，用户会以为它讲的是上周（数字口径和解读必须同一周）。
 * 联网拿到的那段是 AI 写的，本地那段是算出来的小结 —— 两者标题不同，不混。
 */
@Composable
private fun InsightSection(
    insight: CoachInsightResult?,
    isLoading: Boolean,
    weekOffset: Int,
    onReload: () -> Unit,
) {
    if (weekOffset != 0) {
        Text(
            text = stringResource(R.string.ai_review_insight_past_week),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clip(IronHabitShapes.cell)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(IronHabitSpacing.md),
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs)) {
        Text(
            text = stringResource(R.string.ai_insight_section),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            isLoading -> LoadingSkeleton()

            insight == null -> Text(
                text = stringResource(R.string.ai_insight_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                val context = insight.context
                val fromAi: Boolean = insight.source == AdviceSource.REMOTE_LLM
                Text(
                    text = stringResource(
                        if (fromAi) R.string.ai_insight_remote_title else R.string.ai_insight_local_title,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.ai_insight_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        TextButton(onClick = onReload) {
            Text(text = stringResource(R.string.ai_insight_reload))
        }
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
    val sheetState = rememberModalBottomSheetState(
        // 半屏展开时「复制 JSON」整个在折叠区外，得先上滑才够得着（真机走查 #6）。
        skipPartiallyExpanded = true,
    )
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

            // 确认文案画在按钮**上方**：画在下面时它落在按钮行之外，而按钮行已经贴着
            // 弹层底边（1080×1920 实测按钮下沿 y≈1895 / 屏高 1920）→ 永远出不了屏，
            // 用户点了「复制 JSON」看不到任何反应。
            if (copied) {
                Text(
                    text = stringResource(R.string.ai_package_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
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
        }
    }
}

/**
 * 一枚异常 chip：短标签 + 点下去塞进提问框的**完整问题（带真实数字）**。
 *
 * 标签和提问分开是因为 chip 要短到能横着一排看完，而问模型必须把数字给全 ——
 * 只给一句"这周怎么样"，模型只能回一句更空的。
 */
internal data class ReviewChip(
    @StringRes val labelRes: Int,
    val labelArgs: List<Any>,
    @StringRes val questionRes: Int,
    val questionArgs: List<Any>,
)

/**
 * 由周复盘的**异常**驱动 chip（不是每个数字都可点 —— 12 个入口太密）。
 *
 * ⚠️ 每条 chip 的数字都必须从 [WeeklyReview] 里算出来，不许写死：
 * 没数据支撑的 chip 干脆不出现，全周干净就只剩一枚"没有异常"的虚线 chip。
 */
internal fun reviewChips(review: WeeklyReview): List<ReviewChip> {
    val chips = mutableListOf<ReviewChip>()
    val training = review.training

    if (training.plannedDays > 0 && training.completedDays < training.plannedDays) {
        chips += ReviewChip(
            labelRes = R.string.ai_chip_attendance,
            labelArgs = listOf(training.completedDays, training.plannedDays),
            questionRes = R.string.ai_chip_attendance_q,
            questionArgs = listOf(training.completedDays, training.plannedDays),
        )
    }

    training.stalled.maxByOrNull { trend -> trend.stagnantWeeks }?.let { trend ->
        chips += ReviewChip(
            labelRes = R.string.ai_chip_stalled,
            labelArgs = listOf(trend.stagnantWeeks),
            questionRes = R.string.ai_chip_stalled_q,
            questionArgs = listOf(
                trend.exerciseName,
                trend.stagnantWeeks,
                training.totalSets,
                training.plannedSets,
            ),
        )
    }

    // 没记 RPE 的组数：只统计得上动作名的那些行（查不到名字的行本来就不进 items），
    // 所以这是个**下界** —— 宁可少报，也不报一个算不出来的数。
    val setsWithoutRpe: Int = review.days.sumOf { day ->
        day.items.filter { item -> item.rpe == null }.sumOf { item -> item.sets }
    }
    if (setsWithoutRpe > 0) {
        chips += ReviewChip(
            labelRes = R.string.ai_chip_no_rpe,
            labelArgs = listOf(setsWithoutRpe),
            questionRes = R.string.ai_chip_no_rpe_q,
            questionArgs = listOf(setsWithoutRpe),
        )
    }

    if (review.body.sampleCount == 1) {
        chips += ReviewChip(
            labelRes = R.string.ai_chip_one_weighin,
            labelArgs = emptyList(),
            questionRes = R.string.ai_chip_one_weighin_q,
            questionArgs = emptyList(),
        )
    }

    return chips
}

/** 横向可滚的异常 chip 行；没有异常时只留一枚虚线的"问问教练"。 */
@Composable
private fun AnomalyChips(review: WeeklyReview, onAsk: (String) -> Unit) {
    val chips: List<ReviewChip> = reviewChips(review)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        if (chips.isEmpty()) {
            val fallbackQuestion: String = stringResource(R.string.ai_chip_none_q)
            Chip(
                text = stringResource(R.string.ai_chip_none),
                warn = false,
                dashed = true,
                onClick = { onAsk(fallbackQuestion) },
            )
        } else {
            chips.forEach { chip ->
                // 提问文案要在组合期解好：onClick 不是 @Composable 上下文，
                // 在里面调 stringResource 编译不过。
                val question: String =
                    stringResource(chip.questionRes, *chip.questionArgs.toTypedArray())
                Chip(
                    text = stringResource(chip.labelRes, *chip.labelArgs.toTypedArray()),
                    warn = true,
                    dashed = false,
                    onClick = { onAsk(question) },
                )
            }
        }
    }
}

@Composable
private fun Chip(
    text: String,
    warn: Boolean,
    dashed: Boolean,
    onClick: () -> Unit,
) {
    val background = if (warn) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent
    val contentColor = if (warn) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(IronHabitShapes.full)
            .background(background)
            .then(
                if (dashed) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, IronHabitShapes.full)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = IronHabitSpacing.lg, vertical = IronHabitSpacing.sm),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

/**
 * 折叠态那一行摘要。
 *
 * ⚠️ 只拼**真有数据**的片段：没 RPE 就不写 RPE，不写 `RPE 0`、也不写 `RPE —` ——
 * 一个 0 会被读成「强度是 0」，那是编出来的结论。组数是真数，练了 0 组就写 0 组。
 */
@Composable
internal fun collapsedSummary(review: WeeklyReview): String {
    val training: TrainingReview = review.training
    return buildList {
        add(stringResource(R.string.ai_review_summary_sets, training.totalSets))
        training.avgRpe?.let { rpe ->
            add(stringResource(R.string.ai_review_summary_rpe, formatKg(rpe)))
        }
        review.body.deltaKg?.let { delta ->
            // 复用展开态体重格那套符号与取整，别让折叠行和卡片对同一个数写出两种样子
            add(stringResource(R.string.ai_review_summary_weight, weightDeltaText(delta)))
        }
    }.joinToString(" · ")
}

/** 一格指标：`sub` 是数字下面那行小字（体重格用它带 ↓0.8）。 */
private data class MetricCell(
    val label: String,
    val value: String,
    val sub: String? = null,
)

/**
 * 一行指标格。
 *
 * ⚠️ 调用方**只把有数据的格塞进来**：`null` 不渲染成 0、也不渲染成破折号冒充有数
 * （用户定的「磁贴必须诚实」）。所以一行可能 1–4 格，宽度按格数均分。
 */
@Composable
private fun MetricRow(cells: List<MetricCell>) {
    if (cells.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        cells.forEach { cell ->
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cell.value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = cell.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                cell.sub?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 七天热力条。
 *
 * ⚠️ 和「今日」页那条 [com.ironhabit.app.ui.screens.today] 的 `WeekHeatStrip` **口径不同**：
 * 那条的填色来自打卡**行数**，这条来自**完成组数**。所以页面上必须写清依据
 * （见 `ai_review_heat_basis`），否则同一周两屏两种深浅一定被当成 bug。
 *
 * 七格**全部可点**，含计划 0 组也没打卡的空天 —— 空天恰恰是教练最该被问的一天。
 */
@Composable
private fun WeekHeatStrip(
    days: List<WeekDayDetail>,
    todayEpochDay: Long?,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
        ) {
            days.forEachIndexed { index, day ->
                HeatCell(
                    day = day,
                    weekdayIndex = index,
                    isToday = todayEpochDay != null && day.dateEpochDay == todayEpochDay,
                    selected = selected == index,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(index) },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = IronHabitSpacing.xxs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.weekday_short_mon),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.ai_review_heat_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.weekday_short_sun),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeatCell(
    day: WeekDayDetail,
    weekdayIndex: Int,
    isToday: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // 沿用「今日」页磁贴的按压手感（实测 2.44% 缩放）；格子能按就必须给按压反馈。
    val scale by animateFloatAsState(targetValue = if (pressed) 0.975f else 1f, label = "heatCellScale")

    val done: Boolean = day.completedSets > 0 &&
        (day.plannedSets == 0 || day.completedSets >= day.plannedSets)
    val partial: Boolean = day.completedSets > 0 && day.plannedSets > day.completedSets

    val fill: androidx.compose.ui.graphics.Color = when {
        done -> MaterialTheme.colorScheme.primary
        partial -> MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
        isToday -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val labelColor = if (done) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // days 由 buildDays 按周一→周日固定产出 7 项，下标就是星期几，不必再从 epochDay 反推。
    val weekdayRes: Int = WEEKDAY_SHORT_RES[weekdayIndex.coerceIn(0, 6)]

    Box(
        modifier = modifier
            .scale(scale)
            .heightIn(min = 26.dp)
            .clip(IronHabitShapes.cell)
            .background(fill)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, IronHabitShapes.cell)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(weekdayRes),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.padding(vertical = IronHabitSpacing.xs),
        )
    }
}

/** 容量：够一吨就换成吨（四列窄格里 "24300" 放不下，而且吨才是人读的数）。 */
@Composable
private fun capacityText(volumeKg: Float): String = if (volumeKg >= 1000f) {
    stringResource(R.string.ai_review_capacity_tonnes, formatKg(volumeKg / 1000f))
} else {
    stringResource(R.string.ai_review_capacity_kg, formatKg(volumeKg))
}

/** 体重变化：正数 ↑、负数 ↓，都不带正负号（箭头已经表达了方向）。 */
@Composable
private fun weightDeltaText(deltaKg: Float): String =
    stringResource(
        if (deltaKg < 0f) R.string.ai_review_weight_delta_down else R.string.ai_review_weight_delta_up,
        formatKg(kotlin.math.abs(deltaKg)),
    )

private val WEEKDAY_SHORT_RES = listOf(
    R.string.weekday_short_mon, R.string.weekday_short_tue, R.string.weekday_short_wed,
    R.string.weekday_short_thu, R.string.weekday_short_fri, R.string.weekday_short_sat,
    R.string.weekday_short_sun,
)

/** 进步 / 停滞两行；两者都为空 → 返回 `null`（UI 显示"还看不出来"）。 */
@Composable
private fun trendLines(review: WeeklyReview, week: String): String? {
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
        review.training.completedDays > 0 -> stringResource(R.string.ai_review_trend_none, week)
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

/** [ReviewNote] → 一行诚实说明。带「哪一周」的四条由调用方给周名，与卡片标题同源。 */
@Composable
private fun noteText(note: ReviewNote, week: String): String = when (note) {
    ReviewNote.NO_CHECKIN -> stringResource(R.string.ai_review_note_no_checkin, week)
    ReviewNote.NO_RPE -> stringResource(R.string.ai_review_note_no_rpe)
    ReviewNote.NO_WEIGHT -> stringResource(R.string.ai_review_note_no_weight, week)
    ReviewNote.NO_DIET -> stringResource(R.string.ai_review_note_no_diet, week)
    // 「还没过完」只对真实本周成立，UseCase 也只在本周发这条，不需要周名。
    ReviewNote.WEEK_IN_PROGRESS -> stringResource(R.string.ai_review_note_in_progress)
}
