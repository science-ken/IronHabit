package com.ironhabit.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealItem
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 「改这一条」弹层：份量加减 + 挪餐次 + 删除。
 *
 * ## 为什么没有"保存"
 * 每一下都立即落库，弹层里的数字就是库里的数字。记一条食物已经是两下了
 * （加食物 → 点份），再叠一个"改完要点保存"会把误操作变成常态：
 * 改完直接划走弹层，用户以为存了。
 *
 * ## 步长为什么在 ViewModel 而不在这里
 * ±0.5 份 / ±10 g 是**口径**，不是排版；写在这里会让以后接数字键盘的人复制一份。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealItemEditSheet(
    item: MealItem,
    meals: List<Meal>,
    onDismissRequest: () -> Unit,
    onStep: (Boolean) -> Unit,
    onMoveTo: (Meal) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = IronHabitSpacing.xl, vertical = IronHabitSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
        ) {
            Text(
                text = stringResource(R.string.title_edit_meal_item),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.label_meal_item_summary,
                    item.foodName,
                    item.portionLabel,
                    item.nutrition.kcal,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onStep(false) }) {
                    Icon(
                        imageVector = Icons.Filled.Remove,
                        contentDescription = stringResource(R.string.action_portion_less),
                    )
                }
                Text(
                    text = item.portionLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { onStep(true) }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.action_portion_more),
                    )
                }
            }

            // 只有"确实还有别的餐"时才摆这一排：一餐都没有挪的目标，摆出来是空壳。
            val others: List<Meal> = meals.filter { it.id != item.mealId }
            if (others.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.label_item_move_to),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
                ) {
                    others.forEach { meal ->
                        FilterChip(
                            selected = false,
                            onClick = { onMoveTo(meal) },
                            label = { Text(text = stringResource(mealTypeLabelRes(meal.mealType))) },
                        )
                    }
                }
            }

            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.action_delete_meal_item),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
