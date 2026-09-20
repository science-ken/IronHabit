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
import com.ironhabit.app.domain.model.MealIntake
import com.ironhabit.app.domain.model.MealTotals
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlin.math.roundToInt

/**
 * 热量 / 蛋白汇总条（对应预览的 `kcal` / `prot` 与进度条）。
 *
 * 显示**「实际摄入 / 目标」**：分子一律是 [intake]（明细优先 → 打了勾的整餐值 → 0），
 * 分母是规则现算的 [DietTarget]；目标为 0（极端防御）时回落到「计划总量」，避免出现 `x / 0`。
 *
 * @param totals 当日合计 —— **只用它的 `plan*` 当分母兜底**，`intake*` 是"打了勾的整餐值"，不是吃了多少
 * @param target 当日目标（kcal / 蛋白）
 */
@Composable
fun DietTotalsBar(
    totals: MealTotals,
    target: DietTarget,
    /**
     * 实际摄入。**刻意不给默认值** —— 给了就会有人漏传，
     * 而漏传的表现是进度条安安静静地停在 0，看着像"今天还没吃"，
     * 比编译不过难查得多。
     */
    intake: MealIntake,
    modifier: Modifier = Modifier,
) {
    // 分母仍可用 totals 的 plan*（那是"计划要吃多少"，口径没错）；
    // 但**分子必须走 intake** —— totals.intake* 把"打了勾"当"吃了"，
    // 而勾可能只是完成计划的标记，AI 生成完也可能一个都没吃。
    val kcalDenominator: Int =
        if (target.targetKcal > 0) target.targetKcal else totals.planKcal
    val proteinDenominator: Int =
        if (target.targetProtein > 0) target.targetProtein else totals.planProtein.roundToInt()
    val kcalFraction: Float = fraction(intake.kcal.toFloat(), kcalDenominator.toFloat())
    val proteinFraction: Float =
        fraction(intake.proteinG.toFloat(), proteinDenominator.toFloat())

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.label_diet_intake_kcal, intake.kcal, kcalDenominator),
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
                intake.proteinG.roundToInt(),
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
