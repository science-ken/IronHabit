package com.ironhabit.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealItem
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlin.math.roundToInt

/**
 * 餐次卡片：餐次名 + **AI 建议** + **我记的条目** + 合计 + 勾选 / 编辑 / 删除。
 *
 * ## 为什么建议与条目要分成两块、用不同字重
 * 它们是两个事实：建议是"计划吃什么"，条目是"我吃了什么"。
 * 混在一起显示（都写成一行行小字）就会让人分不清哪一份算进了数字 ——
 * 那正是训练区 2026-09-20 修掉的那个 bug 在界面上的样子。
 * 所以：建议一律**灰色小字 + 一行"没记就不计入"的标题**，条目正常字重且带 kcal。
 *
 * ## 底部那个数字有三种写法，语义不同
 * - 有条目 → 条目快照之和（精确）
 * - 没条目但打了勾 → 前面加「约」（粗记，Q31=B）
 * - 都没有 → **不显示数字**，只显示"还没记这餐吃了什么"
 *   （显示建议的 kcal 会被读成"已经吃了"，那是错的）
 *
 * @param loggedItems 这一餐已记录的条目（空 = 还没记）
 * @param onAddFood 打开食物库挑选并记入本餐
 * @param onEditItem 改一条已记条目的份量
 * @param onDeleteItem 删一条已记条目
 * @param enabled 是否可写（`false` = 未来日只读态）
 */
@Composable
fun MealBlock(
    meal: Meal,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    loggedItems: List<MealItem> = emptyList(),
    onAddFood: () -> Unit = {},
    onEditItem: (MealItem) -> Unit = {},
    onDeleteItem: (MealItem) -> Unit = {},
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
                .padding(horizontal = IronHabitSpacing.md, vertical = IronHabitSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = IronHabitSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xxs),
            ) {
                Text(
                    text = stringResource(mealTypeLabelRes(meal.mealType)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (meal.items.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.label_meal_suggestion_header),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    meal.items.forEach { item ->
                        Text(
                            text = "· $item",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                loggedItems.forEach { logged ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { onEditItem(logged) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.label_meal_item_summary,
                                logged.foodName,
                                logged.portionLabel,
                                logged.nutrition.kcal,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (enabled) {
                            IconButton(onClick = { onDeleteItem(logged) }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    // 不是 action_delete_meal（"移除这餐"）：点它只删这一样。
                                    contentDescription = stringResource(R.string.action_delete_meal_item),
                                )
                            }
                        }
                    }
                }

                when {
                    loggedItems.isNotEmpty() -> Text(
                        text = stringResource(
                            R.string.label_meal_kcal,
                            loggedItems.sumOf { item -> item.nutrition.kcal },
                            loggedItems.sumOf { item -> item.nutrition.proteinG }.roundToInt(),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )

                    meal.isCompleted -> Text(
                        text = stringResource(
                            R.string.label_meal_kcal_approx,
                            meal.kcal,
                            meal.proteinG.roundToInt(),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    else -> Text(
                        text = stringResource(R.string.empty_meal_not_logged),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (enabled) {
                    Row(
                        modifier = Modifier
                            .clickable { onAddFood() }
                            .padding(vertical = IronHabitSpacing.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.action_add_food),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xxs),
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
