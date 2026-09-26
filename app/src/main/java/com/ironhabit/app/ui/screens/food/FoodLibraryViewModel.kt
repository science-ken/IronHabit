package com.ironhabit.app.ui.screens.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodServing
import com.ironhabit.app.domain.model.FoodSource
import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.repository.FoodRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * 弹层里的**一份份量**编辑态。
 *
 * 数字用 `String` 存（与 `AddEditExerciseUiState` 同口径）：`TextField` 要能显示"正在输入的空串"，
 * 用 `Int?` 会把"清空"和"0"糊成一团。
 */
data class ServingForm(
    val unit: String = "",
    val grams: String = "",
)

/**
 * 新建 / 编辑一条食物的表单态。
 *
 * 它**不在** [FoodLibraryUiState] 里，而是单独一条流：表单每次按键都会变，
 * 而列表只随仓库变 —— 混在一个 data class 里会让整张列表跟着每敲一个字重组。
 */
data class FoodFormState(
    val foodId: Long = 0L,
    val name: String = "",
    val kcal: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
    val servings: List<ServingForm> = listOf(ServingForm()),
    /**
     * 这条食物**含有**哪些忌口成分（与档案里的「饮食忌口」共用一套词表）。
     *
     * 只对**非内置**条目开放编辑：内置那 127 条的标签是随数据抄进来的，改它等于改数据源，
     * 而"我这条自建的花生酱含 PEANUT"只有用户自己知道 —— 不开放就等于忌口对新条目零保护。
     */
    val dietaryTags: Set<DietRestriction> = emptySet(),
    val isBuiltIn: Boolean = false,
    val nameErrorRes: Int = 0,
    val numberErrorRes: Int = 0,
    val isSaving: Boolean = false,
) {
    val isEditing: Boolean get() = foodId > 0L
}

/**
 * 食物库弹层的 ViewModel。
 *
 * ## 为什么单独一个 VM，而不是塞进 `TodayViewModel`
 * `TodayViewModel.applyData` 是**逐字段手写复制**的，本项目已经因为漏抄一个字段踩过三次
 * （新字段会被静默钉回默认值）。食物库的状态和"今日"没有关系，
 * 放进那个 VM 就是多一个漏抄位。
 *
 * ## 为什么直接调 repository 而不写 UseCase
 * 与 `AddEditHabitViewModel` 一致：这里没有跨仓库编排，包一层 UseCase 只是把一次调用搬个家。
 */
@HiltViewModel
class FoodLibraryViewModel @Inject constructor(
    private val foodRepository: FoodRepository,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoodLibraryUiState())
    val uiState: StateFlow<FoodLibraryUiState> = _uiState.asStateFlow()

    private val _formState = MutableStateFlow(FoodFormState())
    val formState: StateFlow<FoodFormState> = _formState.asStateFlow()

    init {
        // 忌口来自档案（DataStore，不在库里）：它变了要重算沉底那一栏，
        // 否则用户在设置里勾了「海鲜」回到这里还能照挑不误。
        viewModelScope.launch {
            settingsRepository.profile().collect { profile ->
                _uiState.update { it.copy(dietaryAvoid = profile.dietaryAvoid) }
            }
        }
        viewModelScope.launch {
            foodRepository.observeAll().collect { foods ->
                // 启用/停用分两栏而不是混排一栏：整库按拼音排好是用户找东西的依据，
                // 把停用的混进去会让"牛奶"旁边突然多出一条他已经不要的牛奶。
                val (active, inactive) = foods.partition { food -> food.isActive }
                _uiState.update {
                    it.copy(
                        allFoods = sortByPinyin(active),
                        inactiveFoods = sortByPinyin(inactive),
                    )
                }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    /** 展开 / 收起「已停用」那一栏。 */
    fun onToggleInactive() {
        _uiState.update { it.copy(showInactive = !it.showInactive) }
    }

    /** 展开 / 收起「标了你忌口的」那一栏。 */
    fun onToggleRestricted() {
        _uiState.update { it.copy(showRestricted = !it.showRestricted) }
    }

    fun onOpenCreate() {
        _formState.value = FoodFormState()
        _uiState.update { it.copy(editingFoodId = CREATE_SENTINEL) }
    }

    /** 打开一条既有条目进行编辑。 */
    fun onOpenEdit(food: Food) {
        _formState.value = FoodFormState(
            foodId = food.id,
            name = food.name,
            kcal = food.kcalPer100g.toString(),
            protein = trimNumber(food.proteinPer100g),
            carbs = trimNumber(food.carbsPer100g),
            fat = trimNumber(food.fatPer100g),
            servings = food.servings
                .map { serving -> ServingForm(unit = serving.unit, grams = serving.grams.toString()) }
                .ifEmpty { listOf(ServingForm()) },
            dietaryTags = food.dietaryTags,
            isBuiltIn = food.source == FoodSource.BUILT_IN,
        )
        _uiState.update { it.copy(editingFoodId = food.id) }
    }

    fun onFormDismiss() {
        _uiState.update { it.copy(editingFoodId = null) }
    }

    fun onNameChange(value: String) = _formState.update { it.copy(name = value, nameErrorRes = 0) }
    fun onKcalChange(value: String) = _formState.update { it.copy(kcal = value, numberErrorRes = 0) }
    fun onProteinChange(value: String) = _formState.update { it.copy(protein = value, numberErrorRes = 0) }
    fun onCarbsChange(value: String) = _formState.update { it.copy(carbs = value, numberErrorRes = 0) }
    fun onFatChange(value: String) = _formState.update { it.copy(fat = value, numberErrorRes = 0) }

    /** 勾 / 取消一条忌口成分（内置条目不给勾，界面那一格根本不显示）。 */
    fun onTagToggle(tag: DietRestriction) = _formState.update { state ->
        val tags = state.dietaryTags.toMutableSet()
        if (!tags.add(tag)) tags.remove(tag)
        state.copy(dietaryTags = tags)
    }

    fun onServingChange(index: Int, unit: String, grams: String) = _formState.update { state ->
        if (index !in state.servings.indices) {
            state
        } else {
            state.copy(
                servings = state.servings.toMutableList().also { list ->
                    list[index] = ServingForm(unit = unit, grams = grams)
                },
            )
        }
    }

    fun onServingAdd() = _formState.update { it.copy(servings = it.servings + ServingForm()) }

    fun onServingRemove(index: Int) = _formState.update { state ->
        if (index !in state.servings.indices || state.servings.size <= 1) {
            state
        } else {
            state.copy(servings = state.servings.toMutableList().also { it.removeAt(index) })
        }
    }

    /**
     * 保存。校验顺序与 `AddEditExerciseViewModel.onSave` 一致：
     * 名字空 → 数字非法 → 防抖 → 重名 → 落库。
     *
     * 这里**不预先禁用保存键**：整张表有 8 个字段，全部填完才允许点按钮会让人
     * 对着一个灰按钮不知道差在哪。改成"点了才报具体哪一项错"，
     * 错误资源 id 写进 [FoodFormState]，由界面就地显示。
     *
     * @return 成功保存的条目名；`null` = 没保存（错误已写进 [formState]）。
     */
    suspend fun onSave(): String? {
        val state: FoodFormState = _formState.value

        if (state.name.isBlank()) {
            _formState.update { it.copy(nameErrorRes = R.string.error_name_empty) }
            return null
        }
        val parsed: ParsedFood? = parseAndValidate(state)
        if (parsed == null) {
            _formState.update { it.copy(numberErrorRes = R.string.error_invalid_number) }
            return null
        }
        // 防抖：进入即置位，失败路径在 finally 里复位（否则保存失败后按钮永久点不动）。
        if (state.isSaving) return null
        _formState.update { it.copy(isSaving = true) }

        try {
            val name: String = state.name.trim()
            if (foodRepository.nameExists(name, excludeId = state.foodId)) {
                _formState.update { it.copy(nameErrorRes = R.string.error_name_exists) }
                return null
            }
            val existing: Food? = if (state.isEditing) foodRepository.getFood(state.foodId) else null

            foodRepository.upsert(
                Food(
                    id = state.foodId,
                    name = name,
                    kcalPer100g = parsed.kcal,
                    proteinPer100g = parsed.protein,
                    carbsPer100g = parsed.carbs,
                    fatPer100g = parsed.fat,
                    servings = parsed.servings,
                    // 内置条目的标签**不在表单里改**（那 127 条的标签是随数据抄进来的）：
                    // 改个克数就把 PEANUT 清空是安全字段的事故，所以原样带过去。
                    // 非内置（自建 + 外部 AI 建的）走表单里那组 chip —— 只有用户知道这条到底含不含。
                    dietaryTags = if (state.isBuiltIn) existing?.dietaryTags ?: emptySet() else state.dietaryTags,
                    // 内置食物被编辑后**仍是内置**（Q20=B），只置 isUserEdited。
                    source = existing?.source ?: FoodSource.CUSTOM,
                    note = existing?.note,
                    isActive = true,
                    isUserEdited = true,
                    sortOrder = existing?.sortOrder ?: 0,
                    createdAt = existing?.createdAt?.takeIf { it > 0L } ?: nowMillis(),
                ),
            )
            // 成功不另设提示字段：调用方拿到非 null 的返回值就会收起表单，
            // 表单收起本身就是"存好了"的信号（再叠一条 Snackbar 只会盖住列表变化）。
            return name
        } finally {
            _formState.update { it.copy(isSaving = false) }
        }
    }

    /**
     * 停用一条食物（软删：`is_active = 0`，行留在库里）。
     *
     * 顺手把「已停用」展开：否则点完"停用"这一行凭空消失，用户 next 的问题一定是
     * "它去哪了 / 我点错了吗"。展开后它就在同一屏下方继续可见，找回的出口也当场暴露。
     */
    fun onDeactivate(foodId: Long) {
        _uiState.update { it.copy(showInactive = true) }
        viewModelScope.launch { foodRepository.deactivate(foodId) }
    }

    /** 启用回来（#13：停用必须是双向门）。 */
    fun onActivate(foodId: Long) {
        viewModelScope.launch { foodRepository.activate(foodId) }
    }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    private data class ParsedFood(
        val kcal: Int,
        val protein: Double,
        val carbs: Double,
        val fat: Double,
        val servings: List<FoodServing>,
    )

    /** 解析并校验全部数值；任一不合法返回 `null`。 */
    private fun parseAndValidate(state: FoodFormState): ParsedFood? {
        val kcal: Int = state.kcal.trim().toIntOrNull() ?: return null
        if (!InputLimits.isValidFoodKcalPer100G(kcal)) return null

        // 宏量留空 = 0，填了必须能解析且在区间内。
        val protein: Double = parseOptional(state.protein) ?: return null
        val carbs: Double = parseOptional(state.carbs) ?: return null
        val fat: Double = parseOptional(state.fat) ?: return null

        val servings = state.servings.mapIndexedNotNull { index, form ->
            val unit: String = form.unit.trim()
            val gramsText: String = form.grams.trim()
            // 整行空白 = 用户没打算加这一份，直接丢；只填一半 = 非法（会造出算不出营养的份）
            if (unit.isEmpty() && gramsText.isEmpty()) return@mapIndexedNotNull null
            val grams: Int = gramsText.toIntOrNull() ?: return@mapIndexedNotNull null
            if (!InputLimits.isValidServingGrams(grams)) return@mapIndexedNotNull null
            FoodServing(unit = unit, grams = grams, sortOrder = index)
        }
        // 任何一行"填了一半"都会让下面的计数对不上 —— 视为非法，不静默丢。
        val nonBlankRows: Int = state.servings.count { form ->
            form.unit.isNotBlank() || form.grams.isNotBlank()
        }
        if (servings.size != nonBlankRows) return null

        return ParsedFood(kcal, protein, carbs, fat, servings)
    }

    private fun parseOptional(text: String): Double? {
        val trimmed: String = text.trim()
        if (trimmed.isEmpty()) return 0.0
        val value: Double = trimmed.toDoubleOrNull() ?: return null
        return if (InputLimits.isValidFoodMacroPer100G(value)) value else null
    }

    private companion object {
        /** 新建态：表单要打开但没有对应条目，用一个不会与真实 rowid 相撞的哨兵。 */
        const val CREATE_SENTINEL: Long = -1L

        /** 整数值不显示小数点（`13.0` → `13`），与 `formatProtein` 同一考虑。 */
        fun trimNumber(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}
