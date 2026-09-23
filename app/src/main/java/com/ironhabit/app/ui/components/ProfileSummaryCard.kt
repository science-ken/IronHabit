package com.ironhabit.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.ProfileField
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 身体档案概要卡（**只读**、**纯展示**）：标题 + `性别 · 年龄 · 身高 · 目标 · 伤病` 概要 + `›`。
 *
 * 供「AI 教练」页使用。「我的」页的档案卡长得不一样（头像 + 完整度环），
 * 但**概要那一行与它共用 [profileSummaryText]** —— 同一份档案在两页念出同一句话。
 *
 * 设计约束（**后续修改请遵守**）：
 * - **纯展示**：只读 [UserProfile] 派生文案 + 一个点击回调；
 * - **不得**内含任何页面专属逻辑（不得硬编码跳转目标、不得含 AI 页专属文案）——
 *   跳哪里由调用方通过 [onClick] 决定；
 * - 若某个页面需要额外信息（如器械 / 每周天数 / 完整度环），一律**在卡片外面**另加区块，
 *   **不许**往本组件塞参数分支。
 *
 * [equipmentLabelRes] / [injuryLabelRes] 也住在这个文件：档案词汇的中文说法只留一份，
 * 「设置」页的勾选项和这里的概要才不会各翻一遍。
 *
 * 目标恒有值（默认 [Goal.MAINTAIN]），故概要**永不为空**。
 *
 * @param profile 用户档案（未填字段自动省略，不显示占位符）
 * @param onClick 点击回调（跳转目标由调用方决定）
 */
@Composable
fun ProfileSummaryCard(
    profile: UserProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = profileSummaryText(profile)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = IronHabitSpacing.lg, vertical = IronHabitSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.entry_profile_edit),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 档案概要一行：`性别 · 年龄 · 身高 · 目标 · 伤病`，没填的自动省略。
 *
 * 从 [ProfileSummaryCard] 里抽出来单独成一个函数，是因为「我的」页的档案卡要在它前面加头像、
 * 后面加完整度环 —— 那是**页面专属的装饰**，按本文件立的规矩不许塞进卡片组件的参数分支。
 * 但「同一份档案念出同一句话」这条不能破，所以两处共用这个函数
 * （上一轮「计划目标重量」就是两份派生各写一遍漂出来的）。
 */
@Composable
internal fun profileSummaryText(profile: UserProfile): String {
    val genderText = profile.gender?.let { stringResource(genderLabelRes(it)) }
    val ageText = profile.age?.let { "${it}${stringResource(R.string.suffix_profile_age)}" }
    val heightText = profile.heightCm?.let { "${it}${stringResource(R.string.suffix_profile_height)}" }
    val goalText = stringResource(goalLabelRes(profile.goal))
    // 伤病排在最后：它是「注意」而不是「我是谁」，且勾得越多这行越长，不该把目标挤到行尾
    val injuryText: String? = joinLabels(
        resIds = profile.injuryAreas.sortedBy { it.ordinal }.map { injuryLabelRes(it) },
        separator = INJURY_SEPARATOR,
    ).takeIf { it.isNotEmpty() }
    return listOfNotNull(genderText, ageText, heightText, goalText, injuryText)
        .joinToString(SUMMARY_SEPARATOR)
}

/** 性别 → 文案资源。 */
@StringRes
private fun genderLabelRes(gender: Gender): Int = when (gender) {
    Gender.MALE -> R.string.label_profile_gender_male
    Gender.FEMALE -> R.string.label_profile_gender_female
}

/** 目标 → 文案资源。 */
@StringRes
private fun goalLabelRes(goal: Goal): Int = when (goal) {
    Goal.CUT -> R.string.label_profile_goal_cut
    Goal.BULK -> R.string.label_profile_goal_bulk
    Goal.RECOMP -> R.string.label_profile_goal_recomp
    Goal.SHAPE -> R.string.label_profile_goal_shape
    Goal.MAINTAIN -> R.string.label_profile_goal_maintain
}

/** 伤病部位 → 文案资源。 */
@StringRes
internal fun injuryLabelRes(area: InjuryArea): Int = when (area) {
    InjuryArea.KNEE -> R.string.injury_knee
    InjuryArea.LOWER_BACK -> R.string.injury_lower_back
    InjuryArea.SHOULDER -> R.string.injury_shoulder
    InjuryArea.WRIST -> R.string.injury_wrist
    InjuryArea.ELBOW -> R.string.injury_elbow
    InjuryArea.ANKLE -> R.string.injury_ankle
    InjuryArea.NECK -> R.string.injury_neck
    InjuryArea.HIP -> R.string.injury_hip
    InjuryArea.CARDIO -> R.string.injury_cardio
}

/** 器械 → 文案资源。 */
@StringRes
internal fun equipmentLabelRes(equipment: Equipment): Int = when (equipment) {
    Equipment.NONE -> R.string.equipment_none
    Equipment.DUMBBELL -> R.string.equipment_dumbbell
    Equipment.BARBELL -> R.string.equipment_barbell
    Equipment.YOGA_MAT -> R.string.equipment_yoga_mat
    Equipment.PULLUP_BAR -> R.string.equipment_pullup_bar
    Equipment.RESISTANCE_BAND -> R.string.equipment_resistance_band
    Equipment.MACHINE -> R.string.equipment_machine
    Equipment.CABLE -> R.string.equipment_cable
    Equipment.TREADMILL -> R.string.equipment_treadmill
}

/**
 * 完整度判据 → 中文（出现在「我的」页「还差哪几项」那一行）。
 *
 * 与 [equipmentLabelRes] / [injuryLabelRes] 同住：档案词汇的说法只留一份。
 * 这里刻意用**短词**（「器械」而不是设置页那句「可用器械（可多选）」）——
 * 缺口那一行要把最多六项挤进一行，括号里的说明是表单的事。
 */
@StringRes
internal fun profileFieldLabelRes(field: ProfileField): Int = when (field) {
    ProfileField.GENDER -> R.string.label_profile_gender
    ProfileField.AGE -> R.string.label_profile_age
    ProfileField.HEIGHT_CM -> R.string.label_profile_height
    ProfileField.BODY_FAT_PCT -> R.string.label_profile_body_fat
    ProfileField.GOAL_WEIGHT_KG -> R.string.label_profile_goal_weight
    ProfileField.EQUIPMENT -> R.string.field_profile_equipment
}

/** 概要分隔符（纯符号，非中文文案）。 */
internal const val SUMMARY_SEPARATOR = " · "

/**
 * 一串文案资源 id → 中文，用 [separator] 连接。
 *
 * 单独一个函数是因为 `stringResource` **不能**写在 `joinToString { }` 的 lambda 里
 * （Compose 编译器判「非组合上下文」），而 `buildList` + `for` 可以。
 * 伤病与器械两处共用，省得各自再踩一遍。
 */
@Composable
internal fun joinLabels(resIds: List<Int>, separator: String): String =
    buildList { for (id in resIds) add(stringResource(id)) }.joinToString(separator)

/** 伤病之间用斜杠，和概要先分开的「·」区分开：`膝/肩` 是一件事，不是两格档案。 */
private const val INJURY_SEPARATOR = "/"
