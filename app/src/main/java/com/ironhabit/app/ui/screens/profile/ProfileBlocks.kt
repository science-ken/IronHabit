package com.ironhabit.app.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.data.local.AppDatabase
import com.ironhabit.app.domain.model.BodyMetric
import com.ironhabit.app.domain.model.ProfileField
import com.ironhabit.app.domain.model.ProfileLedger
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.ui.components.SUMMARY_SEPARATOR
import com.ironhabit.app.ui.components.ProfileCompletenessRing
import com.ironhabit.app.ui.components.formatMonthDay
import com.ironhabit.app.ui.components.joinLabels
import com.ironhabit.app.ui.components.profileFieldLabelRes
import com.ironhabit.app.ui.components.profileSummaryText
import com.ironhabit.app.ui.screens.settings.toDisplayNumber
import com.ironhabit.app.ui.theme.IronHabitShapes
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 「我的」页首屏（方案 J）的两块读数区：档案卡与关键数字四联。
 *
 * 两块都用 `surfaceContainerHigh` 磁贴底（与今日页同一档），**不**做深浅两档层级 ——
 * 这一屏里最该被看见的是"这页有数字了"，不是"哪个块更重要"。
 *
 * 完整度环的分母来自 [ProfileField] 的项数，不写死：加一项判据，环、缺口行
 * 与 `UserProfileTest` 会一起动，不会出现界面还写着 `/5` 而代码里是 6 项那种漂移。
 */

/** 档案六项判据的总项数（分母的唯一来源）。 */
private val PROFILE_FIELD_TOTAL: Int = ProfileField.entries.size

/**
 * 档案卡：头像 + 概要两行 + 完整度环 + `›`，整卡可点（跳「设置」里的我的档案区）。
 *
 * 头像是**装饰**：档案里没有名字字段，所以既不是首字母也不是头像图，
 * TalkBack 也不会念它（`contentDescription = null`）。
 *
 * 缺口那一行只在**真有缺口**时出现，且排在分隔线下面 —— 它是对环那句
 * "还差几格"的展开，两处必须出自同一份 `missingProfileFields`。
 */
@Composable
internal fun ProfileArchiveCard(
    profile: UserProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val gaps: List<ProfileField> = profile.missingProfileFields
    val gapText: String? = if (gaps.isEmpty()) {
        null
    } else {
        joinLabels(gaps.map { profileFieldLabelRes(it) }, SUMMARY_SEPARATOR)
    }
    val detail: String? = profileDetailText(profile)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(IronHabitShapes.card)
            .background(colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = IronHabitSpacing.md, vertical = IronHabitSpacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .background(colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = colorScheme.onPrimary,
                    modifier = Modifier.size(AVATAR_ICON_SIZE),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profileSummaryText(profile),
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurface,
                    maxLines = MAX_LINES_SUMMARY,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = MAX_LINES_DETAIL,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            ProfileCompletenessRing(done = PROFILE_FIELD_TOTAL - gaps.size, total = PROFILE_FIELD_TOTAL)

            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
            )
        }

        if (gapText != null) {
            HorizontalDivider(
                modifier = Modifier.padding(top = IronHabitSpacing.sm),
                color = colorScheme.outlineVariant,
            )
            Row(
                modifier = Modifier.padding(top = IronHabitSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(GAP_DOT_SIZE)
                        .clip(CircleShape)
                        .background(colorScheme.tertiary),
                )
                Text(
                    text = stringResource(R.string.label_profile_gap_line, gaps.size, gapText),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.tertiary,
                    maxLines = MAX_LINES_DETAIL,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 关键数字四联：连续 → 本周 → 累计 → 体重。
 *
 * 顺序按"今天该练几次"的心智排，不按数据量级排。四格都是**只读数**，
 * 不可点 —— 能点的是下面台账里的行。一格里放一个不能碰的数字，
 * 比一格放一个能点的数字更诚实（原型里那格「体重」能点进身体数据，
 * 但同一屏又有一行「身体数据 ›」，两条路只留显式的那条）。
 *
 * 「本周」在没排课时显示「未排课」而不是「0/0」：分母为 0 的分数不是零，是没定义。
 */
@Composable
internal fun ProfileStatStrip(
    streak: Int,
    weekCompleted: Int,
    weekPlanned: Int,
    totalRecords: Int,
    latestWeight: BodyMetric?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 分隔条要 `fillMaxHeight`，而 Row 默认按内容高测量 → 高度得先用 IntrinsicSize.Min
            // 量出来，否则那根线拿到的约束是无限高（与今日页磁贴同一处理）。
            .height(IntrinsicSize.Min)
            .clip(IronHabitShapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = IronHabitSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StripCell(
            label = stringResource(R.string.label_streak_tile_title),
            value = stringResource(R.string.label_streak_days_value, streak),
        )
        StripSeparator()
        StripCell(
            label = stringResource(R.string.label_profile_week_attendance),
            // 「n / m」这一格式与今日页的「今日完成」「习惯进度」共用同一条串：
            // 同一屏之外的一切比值都该长一个样。环中央那条是另一回事（40dp 里塞不下空格）。
            value = if (weekPlanned > 0) {
                stringResource(R.string.label_progress_ratio, weekCompleted, weekPlanned)
            } else {
                stringResource(R.string.value_profile_fraction_unplanned)
            },
        )
        StripSeparator()
        StripCell(
            label = stringResource(R.string.label_profile_total_records),
            value = stringResource(R.string.value_profile_records_count, totalRecords),
        )
        StripSeparator()
        StripCell(
            label = stringResource(R.string.metric_weight),
            value = latestWeight?.let { weight -> "${weight.value} ${weight.unit}" }
                ?: stringResource(R.string.value_profile_not_recorded),
        )
    }
}

/** 概要第二行：体脂 / 每周几天 / 器械几件，缺哪项就整段不出现（目标恒有值，不进这行）。 */
@Composable
private fun profileDetailText(profile: UserProfile): String? {
    val fatText: String? = profile.bodyFatPct?.let { fat ->
        // 数字用设置页那一格的同一格式（`15` 而不是 `15.0`）：同一个档案字段不该在两页
        // 长成两个样子。四联里的「体重」不走这个格式，那是身体数据记录，与身体数据页同形。
        // ⚠️ 实参先落成局部变量、整句写在一行内：占位符契约测试是**按行**扫调用点的，
        // 跨行会把带参调用读成零参格式化而误报（注释里也别写资源全名，会被当调用点）。
        val fatDisplay: String = fat.toDisplayNumber() + stringResource(R.string.suffix_profile_body_fat)
        stringResource(R.string.label_profile_body_fat_short, fatDisplay)
    }
    // 「每周 N 练」这条串 AI 教练页已经在用，不再另起一条同名文案。
    val daysText: String = stringResource(R.string.ai_profile_weekly_days, profile.trainingDaysPerWeek)
    // 「无器械」是一次表态，件数照样算 1 件；一件都没勾时这行不出现（缺口那一行会说）。
    val equipmentText: String? = profile.equipment.size.takeIf { it > 0 }?.let { count ->
        stringResource(R.string.label_profile_equipment_count, count)
    }
    return listOfNotNull(fatText, daysText, equipmentText)
        .joinToString(SUMMARY_SEPARATOR)
        .takeIf { text -> text.isNotEmpty() }
}

/** 四联里的一格：标签在上、数字在下，与同排其他格平分宽度。 */
@Composable
private fun RowScope.StripCell(label: String, value: String) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xxs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = MAX_LINES_DETAIL,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 四格之间的竖分隔条。 */
@Composable
private fun StripSeparator() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(SEPARATOR_WIDTH)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * 记录台账：四类记录各一行，右侧那个大数是**条数**，缺口显出来。
 *
 * 改版前这四个入口是光板的（写着「食物库」+ 一个箭头），点进去才知道有没有东西。
 * 入口自己带信息之后，"哪一类还没坚持"不用点就能看见。
 */
@Composable
internal fun ProfileLedgerCard(
    ledger: ProfileLedger,
    onOpenTrainingStats: () -> Unit,
    onOpenBodyMetrics: () -> Unit,
    onOpenDiet: () -> Unit,
    onOpenHabits: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val training = ledger.training
    val body = ledger.body
    val diet = ledger.diet
    val habits = ledger.habits
    // 缺口超过一半才用金色那句：它是真话，但不该每天劈头盖脸（2026-09-23 拍板）。
    val dietGapIsBig: Boolean = diet.shortfallRatio > DIET_GAP_ALERT_RATIO

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(IronHabitShapes.card)
            .background(colorScheme.surfaceContainerHigh),
    ) {
        Text(
            text = stringResource(R.string.label_profile_ledger_section),
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = IronHabitSpacing.md,
                end = IronHabitSpacing.md,
                top = IronHabitSpacing.sm,
            ),
        )

        LedgerRow(
            icon = Icons.Filled.BarChart,
            name = stringResource(R.string.entry_training_stats),
            detail = stringResource(
                R.string.label_ledger_training_detail,
                training.setCount,
                training.repCount,
                training.rpeRowCount,
                training.activeDayCount,
            ),
            detailIsGap = false,
            count = stringResource(R.string.value_profile_records_count, training.rowCount),
            lastDate = training.lastEpochDay?.let { day ->
                stringResource(R.string.label_last_date_short, formatMonthDay(day))
            },
            onClick = onOpenTrainingStats,
        )
        LedgerDivider()

        LedgerRow(
            icon = Icons.Filled.MonitorWeight,
            name = stringResource(R.string.entry_body_metrics),
            detail = stringResource(R.string.label_ledger_body_detail),
            detailIsGap = false,
            count = stringResource(R.string.value_profile_records_count, body.rowCount),
            lastDate = body.lastEpochDay?.let { day ->
                stringResource(R.string.label_last_date_short, formatMonthDay(day))
            },
            onClick = onOpenBodyMetrics,
        )
        LedgerDivider()

        LedgerRow(
            icon = Icons.Filled.Restaurant,
            name = stringResource(R.string.entry_diet),
            detail = if (dietGapIsBig) {
                stringResource(R.string.label_ledger_diet_gap, diet.mealRowCount, diet.itemCount, diet.kcal)
            } else {
                stringResource(R.string.label_ledger_diet_detail, diet.filledMealCount, diet.itemCount, diet.kcal)
            },
            detailIsGap = dietGapIsBig,
            count = stringResource(R.string.value_profile_fraction, diet.filledMealCount, diet.mealRowCount),
            lastDate = diet.lastFilledEpochDay?.let { day ->
                stringResource(R.string.label_last_date_short, formatMonthDay(day))
            },
            onClick = onOpenDiet,
        )
        LedgerDivider()

        LedgerRow(
            icon = Icons.Filled.SelfImprovement,
            name = stringResource(R.string.title_habits),
            detail = stringResource(R.string.label_ledger_habit_detail, habits.activeHabits, habits.completedLogs),
            detailIsGap = false,
            count = stringResource(R.string.value_profile_logs_count, habits.completedLogs),
            lastDate = habits.lastEpochDay?.let { day ->
                stringResource(R.string.label_last_date_short, formatMonthDay(day))
            },
            onClick = onOpenHabits,
        )
    }
}

/**
 * 「存在哪儿」+ 三个应用内入口。
 *
 * 那句"没有云同步；卸载即清零"是单人离线应用最该被一眼看到的Fact，
 * 此前只写在备份页里 —— 而大多数人根本不会点进备份页。
 *
 * 库文件多大（字节数）没放进来：它要在 ViewModel 里注入 Context 读文件系统，
 * 而这一屏要的是"数据只在这台机器上"这件事，不是那个数。
 * schema 版本用 `AppDatabase.VERSION` 而不是写死，否则下一次迁移之后这里就开始说谎。
 */
@Composable
internal fun ProfileStorageCard(
    onOpenBackup: () -> Unit,
    onOpenFoodLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    // 两句各自落成一行：占位符契约测试是按行数实参的，嵌套调用 + 跨行会被它读成零参格式化。
    val schemaVersion: String = stringResource(R.string.label_schema_version, AppDatabase.VERSION)
    val storageTitle: String = stringResource(R.string.label_profile_storage_title, schemaVersion)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(IronHabitShapes.card)
            .background(colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IronHabitSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = storageTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.label_profile_storage_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenBackup) {
                Text(text = stringResource(R.string.entry_backup))
            }
        }
        LedgerDivider()
        CompactEntryRow(text = stringResource(R.string.entry_food_library), onClick = onOpenFoodLibrary)
        LedgerDivider()
        CompactEntryRow(
            text = stringResource(R.string.label_profile_settings_row),
            onClick = onOpenSettings,
        )
    }
}

/** 台账里的一行：图标 + 名称 + 副标题 + 右侧条数（可带最近日）+ `›`。 */
@Composable
private fun LedgerRow(
    icon: ImageVector,
    name: String,
    detail: String,
    detailIsGap: Boolean,
    count: String,
    lastDate: String?,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = IronHabitSpacing.md, vertical = IronHabitSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(LEDGER_ICON_BOX)
                .clip(IronHabitShapes.small)
                .background(colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colorScheme.onPrimaryContainer,
                modifier = Modifier.size(LEDGER_ICON),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (detailIsGap) colorScheme.tertiary else colorScheme.onSurfaceVariant,
                maxLines = MAX_LINES_DETAIL,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = count,
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                maxLines = 1,
            )
            if (lastDate != null) {
                Text(
                    text = lastDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }

        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
        )
    }
}

/** 卡片内行与行之间的分隔线：左右缩进，不顶到卡片圆角。 */
@Composable
private fun LedgerDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = IronHabitSpacing.md),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 底部那两行紧凑入口（食物库 / 设置）：只要名称和箭头，不需要条数。 */
@Composable
private fun CompactEntryRow(
    text: String,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = IronHabitSpacing.md, vertical = IronHabitSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurface,
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
        )
    }
}

// ---- 以下为组件固有尺寸（非布局间距），按 `Spacing.kt` 的例外约定就地定义 ----

private val AVATAR_SIZE = 40.dp
private val AVATAR_ICON_SIZE = 22.dp
private val GAP_DOT_SIZE = 6.dp
private val SEPARATOR_WIDTH = 1.dp
private val LEDGER_ICON_BOX = 32.dp
private val LEDGER_ICON = 18.dp
private const val MAX_LINES_SUMMARY = 2
private const val MAX_LINES_DETAIL = 2

/** 饮食缺口超过这个比例才用金色那句，否则只报普通摘要。 */
private const val DIET_GAP_ALERT_RATIO = 0.5f
