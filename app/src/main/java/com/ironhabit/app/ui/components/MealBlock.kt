package com.ironhabit.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import kotlin.math.roundToInt

/**
 * 餐次卡片（对应预览 `mealBlock()`）：餐次名 + 多条食物条目 + 整餐热量/蛋白 + 完成勾选 + 编辑 + 删除。
 *
 * **只读态**（[enabled] = `false`，用于「所选日 > 今天」）：禁用勾选 / 编辑 / 删除三个写入口。
 *
 * @param meal 一餐（含条目列表 / kcal / 蛋白 / 完成态）
 * @param onToggle 勾选 / 取消（参数 = 勾选后的状态）
 * @param onEdit 编辑这一餐的内容（条目 / 热量 / 蛋白；改动会置 `isUserEdited`，重新生成时跳过）
 * @param onDelete 删除这餐（软删除，UI 侧入口）
 * @param enabled 是否可写（`false` = 未来日只读态）
 */
@Composable
fun MealBlock(
    meal: Meal,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(mealTypeLabelRes(meal.mealType)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                meal.items.forEach { item ->
                    Text(
                        text = "· $item",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.label_meal_kcal,
                        meal.kcal,
                        meal.proteinG.roundToInt(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Checkbox(
                    checked = meal.isCompleted,
                    onCheckedChange = onToggle,
                    enabled = enabled,
                )
                IconButton(onClick = onEdit, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.action_edit_meal),
                    )
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.action_delete_meal),
                    )
                }
            }
        }
    }
}

/**
 * 餐次 → 文案资源 id（早餐 / 午餐 / 加餐 / 晚餐）。
 *
 * ⚠️ 餐次名属 **UI 文案**，一律走 `strings.xml`（架构 §7.5），**禁止硬编码中文**。
 */
fun mealTypeLabelRes(type: MealType): Int = when (type) {
    MealType.BREAKFAST -> R.string.meal_breakfast
    MealType.LUNCH -> R.string.meal_lunch
    MealType.SNACK -> R.string.meal_snack
    MealType.DINNER -> R.string.meal_dinner
}
