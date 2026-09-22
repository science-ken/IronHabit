package com.ironhabit.app.ui.screens.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodServing
import com.ironhabit.app.domain.model.FoodSource
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlinx.coroutines.launch

/**
 * 食物库弹层：浏览 / 搜索 / 新建 / 编辑 / 停用。
 *
 * ⚠️ **本刀（第 1 刀）它只做"库"的事，不做"记进这一餐"** ——
 * 把选中的食物写进一餐是第 2 刀的 `meal_items`，现在点条目是**打开编辑**，不是加入。
 * 这不是遗漏：`meal_items` 还没建，先放一个"点了没反应"的按钮比骗用户好。
 *
 * 弹层必须可滚动 + `imePadding`：这条是从 `MealEditSheet` 真机踩来的
 * （字段一多，保存/取消会被裁到够不着）。
 */
/**
 * 「今日饮食」弹层顶部的**食物库入口条**。
 *
 * 为什么放顶部而不是底部一行小字（真机实测）：饮食弹层默认**半展开**，
 * 四张餐卡就把可视区占满，底部那行按钮**连节点都不会被渲染出来**
 * —— 不是"看不见"，是"没进视图层级"，用户不往上滑就永远找不到它。
 *
 * 条数直接读 [FoodLibraryViewModel]：它与下面的弹层**是同一个实例**
 * （同属今日这个导航目的地，`hiltViewModel()` 走同一个 ViewModelStore），
 * 所以不必给 `TodayUiState` 加字段 —— 那个 VM 的 `applyData` 是逐字段手写复制的，
 * 本项目已经漏抄过三次。
 */
@Composable
fun FoodLibraryEntry(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodLibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    OutlinedButton(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.title_food_library),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.label_food_library_count, uiState.allFoods.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 装饰性指示符，与 `TodayBento` / `ProfileSummaryCard` 同一写法：
            // `Text("›")` 会作为真实文本节点进无障碍树，TalkBack 每划过一次就念一个无用符号。
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 食物库弹层：浏览 / 搜索 / 新建 / 编辑 / 停用（含停用后启用回来），**或**给某一餐挑食物。
 *
 * ## 两种模式
 * [onPick] 为 `null` 时是"管库"：点条目 = 打开编辑。
 * 传了 [onPick] 就是"挑食物记进这一餐"：每行下面摊出它的份（一碗 / 一盘）当按钮，
 * **点一下就记进去**，弹层不关（一顿饭通常要记好几样）。
 * 没有份的食物走 [onPick] 的 `serving = null` 分支，由调用方按克数处理。
 *
 * 为什么点一下就加、不要"选完再确定"：用户 Q1 选的是"每天真的要记"，
 * 一顿三样东西的代价必须是三下，不能变成 3×2 + 1 下。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodLibrarySheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    onPick: ((Food, FoodServing?) -> Unit)? = null,
    viewModel: FoodLibraryViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val formState by viewModel.formState.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        val editing: Boolean = uiState.editingFoodId != null
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = IronHabitSpacing.xl, vertical = IronHabitSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
        ) {
            if (editing) {
                FoodFormSection(
                    formState = formState,
                    viewModel = viewModel,
                    onSaved = { viewModel.onFormDismiss() },
                )
            } else {
                FoodListSection(
                    uiState = uiState,
                    viewModel = viewModel,
                    onPick = onPick,
                )
            }
        }
    }
}

@Composable
private fun FoodListSection(
    uiState: FoodLibraryUiState,
    viewModel: FoodLibraryViewModel,
    onPick: ((Food, FoodServing?) -> Unit)? = null,
) {
    val picking: Boolean = onPick != null

    Text(
        text = stringResource(
            if (picking) R.string.title_pick_food_for_meal else R.string.title_food_library
        ),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )

    OutlinedTextField(
        value = uiState.query,
        onValueChange = { viewModel.onQueryChange(it) },
        label = { Text(text = stringResource(R.string.hint_food_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedButton(
        onClick = { viewModel.onOpenCreate() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.action_new_food))
    }

    // 放在列表**上方**而不是末尾：启用中的食物有几十条，滚到底才看到"这里能找回停用的"
    // 等于没有 —— 用户是在列表顶上发现东西不见了，不是在列表底下。
    if (uiState.inactiveFoods.isNotEmpty() && !picking) {
        FilterChip(
            selected = uiState.showInactive,
            onClick = { viewModel.onToggleInactive() },
            label = {
                Text(text = stringResource(R.string.chip_food_inactive_count, uiState.inactiveFoods.size))
            },
        )
    }

    if (uiState.nothingToShow) {
        Text(
            text = stringResource(
                if (uiState.query.isBlank()) R.string.empty_food_library else R.string.empty_food_search_no_match
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    uiState.visibleFoods.forEach { food ->
        if (onPick != null) {
            FoodPickRow(food = food, onPick = onPick)
        } else {
            FoodRow(
                food = food,
                onEdit = { viewModel.onOpenEdit(food) },
                onDeactivate = { viewModel.onDeactivate(food.id) },
            )
        }
    }

    // 停用行只给「启用」，不给「编辑」：`onSave` 保存时恒定写 `isActive = true`，
    // 从停用行进表单会"只是改个热量，它却自己回来了"。要改就先启用，那是一次明确的意图。
    if (!picking) {
        uiState.visibleInactiveFoods.forEach { food ->
            FoodInactiveRow(
                food = food,
                onActivate = { viewModel.onActivate(food.id) },
            )
        }
    }

    Box(modifier = Modifier.padding(bottom = IronHabitSpacing.lg))
}

/**
 * 已停用的一行：名字压暗、第二行标明「已停用」、右侧只留一个「启用」。
 *
 * 不列份量：这行要回答的只有"这条我还能要不回来吗"，摊开克数只会让它和启用中的长得一样。
 */
@Composable
private fun FoodInactiveRow(
    food: Food,
    onActivate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = IronHabitSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = food.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.label_food_inactive_summary, food.kcalPer100g),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onActivate) {
            Text(text = stringResource(R.string.action_activate))
        }
    }
}

/**
 * 挑选模式的一行：名称 + 每 100g 热量 + **每个份一个按钮**。
 *
 * 点份就直接记进去一份；没有份的食物给一个"按克数"，
 * 由调用方按 100g 起记（真正的克数编辑在条目行上改）。
 */
@Composable
private fun FoodPickRow(
    food: Food,
    onPick: (Food, FoodServing?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = IronHabitSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
    ) {
        Text(
            text = food.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.label_food_per_100g_summary, food.kcalPer100g),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            if (food.hasServing) {
                food.servings.forEach { serving ->
                    OutlinedButton(onClick = { onPick(food, serving) }) {
                        Text(text = "1" + serving.unit)
                    }
                }
            } else {
                OutlinedButton(onClick = { onPick(food, null) }) {
                    Text(text = stringResource(R.string.action_log_by_grams))
                }
            }
        }
    }
}

@Composable
private fun FoodRow(
    food: Food,
    onEdit: () -> Unit,
    onDeactivate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = IronHabitSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = food.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (food.source == FoodSource.BUILT_IN) {
                    Text(
                        text = stringResource(R.string.label_food_built_in),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.label_food_per_100g_summary, food.kcalPer100g),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (food.hasServing) {
                Text(
                    text = servingSummaryText(food.servings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onEdit) {
            Text(text = stringResource(R.string.action_edit))
        }
        TextButton(onClick = onDeactivate) {
            Text(text = stringResource(R.string.action_deactivate))
        }
    }
}

@Composable
private fun FoodFormSection(
    formState: FoodFormState,
    viewModel: FoodLibraryViewModel,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Text(
        text = stringResource(
            if (formState.isEditing) R.string.title_edit_food else R.string.title_add_food
        ),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )

    OutlinedTextField(
        value = formState.name,
        onValueChange = { viewModel.onNameChange(it) },
        label = { Text(text = stringResource(R.string.hint_food_name)) },
        singleLine = true,
        isError = formState.nameErrorRes != 0,
        modifier = Modifier.fillMaxWidth(),
    )
    if (formState.nameErrorRes != 0) {
        FormError(formState.nameErrorRes)
    }

    OutlinedTextField(
        value = formState.kcal,
        onValueChange = { viewModel.onKcalChange(it) },
        label = { Text(text = stringResource(R.string.hint_food_kcal)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = formState.numberErrorRes != 0,
        modifier = Modifier.fillMaxWidth(),
    )
    NumberField(
        labelRes = R.string.hint_food_protein,
        value = formState.protein,
        onChange = { viewModel.onProteinChange(it) },
        hasError = formState.numberErrorRes != 0,
    )
    NumberField(
        labelRes = R.string.hint_food_carbs,
        value = formState.carbs,
        onChange = { viewModel.onCarbsChange(it) },
        hasError = formState.numberErrorRes != 0,
    )
    NumberField(
        labelRes = R.string.hint_food_fat,
        value = formState.fat,
        onChange = { viewModel.onFatChange(it) },
        hasError = formState.numberErrorRes != 0,
    )
    if (formState.numberErrorRes != 0) {
        FormError(formState.numberErrorRes)
    }

    Text(
        text = stringResource(R.string.label_food_servings),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    formState.servings.forEachIndexed { index, serving ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            OutlinedTextField(
                value = serving.unit,
                onValueChange = { viewModel.onServingChange(index, it, serving.grams) },
                label = { Text(text = stringResource(R.string.hint_food_serving_unit)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = serving.grams,
                onValueChange = { viewModel.onServingChange(index, serving.unit, it) },
                label = { Text(text = stringResource(R.string.hint_food_serving_grams)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            if (formState.servings.size > 1) {
                TextButton(onClick = { viewModel.onServingRemove(index) }) {
                    Text(text = stringResource(R.string.action_remove))
                }
            }
        }
    }
    TextButton(onClick = { viewModel.onServingAdd() }) {
        Text(text = stringResource(R.string.action_add_serving))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = IronHabitSpacing.lg),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onSaved) {
            Text(text = stringResource(R.string.action_cancel))
        }
        Button(
            onClick = {
                // onSave() 是 suspend（要查重名、要写库），只能在作用域里跑；
                // 保存成功才关表单，失败时错误已经写进 formState，留着让用户改。
                scope.launch {
                    if (viewModel.onSave() != null) onSaved()
                }
            },
            enabled = !formState.isSaving,
            modifier = Modifier.padding(start = IronHabitSpacing.sm),
        ) {
            Text(text = stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun NumberField(
    labelRes: Int,
    value: String,
    onChange: (String) -> Unit,
    hasError: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(text = stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = hasError,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FormError(messageRes: Int) {
    Text(
        text = stringResource(messageRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * 一行显示全部份量："1盒 = 250 g、1杯 = 200 g"。
 *
 * 单独抽出来是因为 `stringResource` 是 `@Composable`，**不能在 `joinToString` 的
 * 转换 lambda 里调用**（那个 lambda 不是 inline 的，编译期直接报
 * "@Composable invocations can only happen from the context of a @Composable function"）；
 * `map` 是 inline 的，所以放在这里先映射、再拼接。
 */
@Composable
private fun servingSummaryText(servings: List<FoodServing>): String =
    servings.map { serving ->
        stringResource(R.string.label_food_serving_summary, serving.unit, serving.grams)
    }.joinToString("、")
