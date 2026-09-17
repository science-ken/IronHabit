package com.ironhabit.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.MealTotals
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlin.math.roundToInt

/**
 * 热量 / 蛋白汇总条（对应预览的 `kcal` / `prot` 与进度条）。
 *
 * 显示**「已摄入 / 目标」**两个口径：已摄入只算勾选完成的餐（[MealTotals.intakeKcal]），
 * 目标来自规则现算的 [DietTarget]。目标为 0（极端防御）时回落到「计划总量」作为分母，
 * 避免出现 `x / 0`。
 *
 * @param totals 当日合计（已摄入 / 计划）
 * @param target 当日目标（kcal / 蛋白）
 */
@Composable
fun DietTotalsBar(
    totals: MealTotals,
    target: DietTarget,
    modifier: Modifier = Modifier,
) {
    val kcalDenominator: Int =
        if (target.targetKcal > 0) target.targetKcal else totals.planKcal
    val proteinDenominator: Int =
        if (target.targetProtein > 0) target.targetProtein else totals.planProtein.roundToInt()
    val kcalFraction: Float = fraction(totals.intakeKcal.toFloat(), kcalDenominator.toFloat())
    val proteinFraction: Float =
        fraction(totals.intakeProtein.toFloat(), proteinDenominator.toFloat())

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.label_diet_intake_kcal, totals.intakeKcal, kcalDenominator),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LinearProgressIndicator(
            progress = { kcalFraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(
                R.string.label_diet_intake_protein,
                totals.intakeProtein.roundToInt(),
                proteinDenominator,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LinearProgressIndicator(
            progress = { proteinFraction },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 进度比例，钳制到 `[0, 1]`；分母 ≤ 0 时返回 0（避免除零）。 */
private fun fraction(value: Float, denominator: Float): Float =
    if (denominator <= 0f) 0f else (value / denominator).coerceIn(0f, 1f)
