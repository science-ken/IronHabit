package com.ironhabit.app.ui.screens.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.ai.external.ExternalDocRefusal
import com.ironhabit.app.domain.ai.external.ExternalPlanNote
import com.ironhabit.app.domain.ai.external.ImportSection
import com.ironhabit.app.domain.ai.external.ImportedNewExercise
import com.ironhabit.app.domain.ai.external.ImportedNewFood
import com.ironhabit.app.domain.ai.external.NewExerciseCandidate
import com.ironhabit.app.domain.ai.external.NewFoodCandidate
import com.ironhabit.app.domain.ai.external.toCandidate
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.usecase.BuildExternalCoachPromptUseCase
import com.ironhabit.app.domain.usecase.BuildExternalDietPromptUseCase
import com.ironhabit.app.domain.usecase.CreateImportedExercisesUseCase
import com.ironhabit.app.domain.usecase.CreateImportedFoodsUseCase
import com.ironhabit.app.domain.usecase.ExternalPlanImport
import com.ironhabit.app.domain.usecase.ImportExternalPlanUseCase
import com.ironhabit.app.domain.usecase.PlanPreviewHolder
import com.ironhabit.app.domain.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 「导入外部 AI 的计划」这一条通道的状态（AI 教练页上的一张弹层）。
 *
 * 单独成 ViewModel 而不是塞进 [AiCoachViewModel]：这条路**一次网络都不发**、也不读档案流，
 * 和教练页那套"开屏就要算复盘/解读"的生命周期没有共同状态；混在一起只会让两边的
 * 加载标志互相踩。两者唯一的交接点是 [PlanPreviewHolder] —— 解析成功后把草案交给它，
 * 再走**同一个**「本周计划预览」页逐天采纳。
 *
 * ## 四条诚实约束（每条都对应界面上一句话）
 * 1. 模板由本地数据拼出来，不谎称"AI 已经帮你排好"；
 * 2. 解析丢掉的每一条都要摊在预览页上（[ExternalPlanNote]），不许静默少写；
 * 3. 导入的行算「AI 生成的行」，预览页必须写明"以后重新生成会被覆盖"；
 * 4. 模型给的食物营养数值只是**预填**（[NewFoodCandidate]），用户当场认过才落库 ——
 *    一餐的 kcal / 蛋白永远由食物库每 100g × 克数现算。
 */
@HiltViewModel
class ExternalImportViewModel @Inject constructor(
    private val buildPromptTemplate: BuildExternalCoachPromptUseCase,
    private val buildDietPrompt: BuildExternalDietPromptUseCase,
    private val importPlan: ImportExternalPlanUseCase,
    private val createExercises: CreateImportedExercisesUseCase,
    private val createFoods: CreateImportedFoodsUseCase,
    private val planPreviewHolder: PlanPreviewHolder,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    /** 只给两个选项：「每周相同」模板（`week_start = 0`）和已经过完的周都不作为落点。 */
    enum class WeekChoice { THIS_WEEK, NEXT_WEEK }

    /** 建食物库表单里正在改的那一格（界面上是 5 个输入框，值一律先当字符串收）。 */
    enum class NewFoodField { NAME, KCAL, PROTEIN, CARBS, FAT }

    data class UiState(
        val sheetOpen: Boolean = false,
        /**
         * 这一张弹层是哪个入口开的 —— 决定复制哪份模板、按哪份合同判。
         *
         * 两个入口互不相认（粘错当场 `WRONG_SCHEMA`），所以这个值不能靠猜：
         * 它只在 [open] 里被赋值，弹层开着的时候不会自己变。
         */
        val mode: ImportSection = ImportSection.TRAINING,
        val week: WeekChoice = WeekChoice.THIS_WEEK,
        /** 拼好的提问模板；`null` = 还没要过（用户点「复制提问模板」才算）。 */
        val template: String? = null,
        val isBuildingTemplate: Boolean = false,
        @StringRes val templateFailedRes: Int? = null,
        /** 用户粘进来的原文。 */
        val text: String = "",
        val isParsing: Boolean = false,
        /** 整份拒收时的那一句说明；`null` = 没有拒收。 */
        @StringRes val refusalRes: Int? = null,
        /** 模型在 `analysis` 里自己说的话，跟着拒收一起摊开（它常常就是原因）。 */
        val refusalAnalysis: String? = null,
        /** 逐条"什么没进来 / 什么被动过"。拒收时也可能非空（全被挡掉那一种）。 */
        val notes: List<ExternalPlanNote> = emptyList(),
        /**
         * 库里没有、但文档在 `newExercises` 里声明过的动作。
         *
         * 默认一行都不勾：建动作是往用户库里**永久加一行**，文档"想要"不等于用户"同意"。
         */
        val newExercises: List<NewExerciseCandidate> = emptyList(),
        /**
         * 食物库里没有的那些食物，连同**建库表单**（预填 / 可改 / 勾选 / 忌口标签）。
         *
         * 表单必须内嵌在这张弹层里，跳不出去：AI 教练页没有任何到食物库的路由，
         * 而切 Tab 会销毁这个 VM、粘贴框里的原文随之没掉。
         */
        val newFoods: List<NewFoodCandidate> = emptyList(),
        /** 有没建的动作/食物、但剩下的已经可以导 → 给一条"先只导入能导的"退路。 */
        val canProceedWithoutThem: Boolean = false,
        val isCreating: Boolean = false,
        @StringRes val snackbarRes: Int? = null,
        val snackbarArgs: List<Any> = emptyList(),
        /** 草案已放进 holder → 界面跳一次预览页。 */
        val previewRequested: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 拼模板要的复盘（教练页已经算好的一份，绝不自己再算一次）。私有：只影响拼不拼得出。 */
    private var lastReview: WeeklyReview? = null

    /** 正在跑的拼装任务：换周时取消它，避免迟到的旧结果盖掉新周的那一份。 */
    private var buildJob: Job? = null

    /**
     * 解析成功、但文档里还有没建的动作时暂存的结果。
     *
     * 用户可以在弹层里直接选"先只导入能导的"，不必为了跳页再解析一次。
     */
    private var pending: ExternalPlanImport.Ready? = null

    /** 选定那一周的周一 epochDay（界面用它算「本周 9/21–9/27」这类标签）。 */
    fun weekStartEpochDay(choice: WeekChoice = _uiState.value.week): Long {
        val today: LocalDate = clock.now().toLocalDateTime(timeZone).date
        val thisMonday: Long = DateUtils.weekStartMon1(today.toEpochDays().toLong())
        return if (choice == WeekChoice.THIS_WEEK) thisMonday else thisMonday + 7L
    }

    /**
     * 打开时就把手里的复盘存下来并**立刻拼一次模板**。
     *
     * ⚠️ 不能等用户点「复制提问模板」才开始拼：真机上第一次点因此毫无反应
     *（按钮文案还是"复制提问模板"、剪贴板也没动），用户读解成"按钮坏了"再点一次才成功。
     * 打开弹层这一动作本身就是"我要模板"，拼装在它背后跑掉。
     */
    fun open(review: WeeklyReview?, mode: ImportSection = ImportSection.TRAINING) {
        lastReview = review
        _uiState.update { it.copy(sheetOpen = true, mode = mode) }
        refreshTemplate()
    }

    /** 复盘算完得比弹层打开晚时（首帧那一瞬），由界面把新的复盘补进来再拼一次。 */
    fun onReviewAvailable(review: WeeklyReview) {
        if (lastReview === review) return
        lastReview = review
        if (_uiState.value.sheetOpen) refreshTemplate()
    }

    /** 关闭时把模板与拒收说明一起清掉：下次进来不能还挂着上一次的结论。 */
    fun dismiss() {
        buildJob?.cancel()
        pending = null
        _uiState.update {
            it.copy(
                sheetOpen = false,
                template = null,
                isBuildingTemplate = false,
                templateFailedRes = null,
                refusalRes = null,
                refusalAnalysis = null,
                notes = emptyList(),
                newExercises = emptyList(),
                newFoods = emptyList(),
                canProceedWithoutThem = false,
            )
        }
    }

    /** 换周 → 模板必须重算：模板里"这一周已经排了什么"那一段是按周拼的。 */
    fun onWeekChange(choice: WeekChoice) {
        _uiState.update { it.copy(week = choice) }
        refreshTemplate()
    }

    fun onTextChange(text: String) {
        _uiState.update {
            it.copy(
                text = text,
                refusalRes = null,
                refusalAnalysis = null,
                notes = emptyList(),
                newExercises = emptyList(),
                newFoods = emptyList(),
            )
        }
    }

    /** 「读取剪贴板」按下的结果由 UI 层传进来（剪贴板只能从 Compose 侧读）。 */
    fun onClipboardRead(clipboardText: String?) {
        if (clipboardText.isNullOrBlank()) {
            _uiState.update { it.copy(snackbarRes = R.string.ai_import_clipboard_empty) }
            return
        }
        _uiState.update {
            it.copy(
                text = clipboardText,
                refusalRes = null,
                refusalAnalysis = null,
                notes = emptyList(),
                newExercises = emptyList(),
                newFoods = emptyList(),
            )
        }
    }

    /**
     * 拼提问模板（复盘用 [lastReview]：那是页面已经算好的那一份，这里绝不重算）。
     *
     * 复盘还没算出来时不发请求也不报错 —— 此刻按钮上写的是"正在整理你的数据…"。
     */
    fun refreshTemplate() {
        val review: WeeklyReview = lastReview ?: return
        val week: Long = weekStartEpochDay()
        // 换周会取消上一次还在跑的拼装：拼装结果和"哪一周"绑死，迟到的旧结果盖上去
        // 就是拿本周的现状配下周的模板。
        buildJob?.cancel()
        _uiState.update { it.copy(isBuildingTemplate = true, template = null, templateFailedRes = null) }
        buildJob = viewModelScope.launch {
            val text: String? = try {
                // 两份模板各自独立：训练那份只管练，饮食那份只管吃。
                // 合在一条长提示里两头要，模型对后半段的遵循度明显差（真实回答把吃写进了 nutrition）。
                if (_uiState.value.mode == ImportSection.DIET) {
                    buildDietPrompt(week)
                } else {
                    buildPromptTemplate(review, week)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Exception) {
                null
            }
            _uiState.update {
                it.copy(
                    isBuildingTemplate = false,
                    template = text,
                    templateFailedRes = if (text == null) R.string.ai_import_template_failed else null,
                )
            }
        }
    }

    fun onTemplateCopied() {
        _uiState.update { it.copy(snackbarRes = R.string.ai_import_template_copied) }
    }

    /**
     * 解析粘回来的文档。**只算不写**：成功时把草案 + 清单放进 [PlanPreviewHolder]，
     * 由预览页逐天采纳 —— 和内置生成同一条闸门，外部来源不因为"是用户自己问来的"就免审。
     */
    fun parse() {
        if (_uiState.value.isParsing) return
        val text: String = _uiState.value.text
        val weekStart: Long = weekStartEpochDay()
        // 上一轮的结论全部作废（包括攒着没跳的草案）：改了粘贴框就该重新判定。
        pending = null
        _uiState.update {
            it.copy(
                isParsing = true,
                refusalRes = null,
                refusalAnalysis = null,
                notes = emptyList(),
                newExercises = emptyList(),
                newFoods = emptyList(),
                canProceedWithoutThem = false,
            )
        }

        viewModelScope.launch {
            val result: ExternalPlanImport = importPlan(text, weekStart, _uiState.value.mode)

            when (result) {
                is ExternalPlanImport.Refused -> _uiState.update {
                    it.copy(
                        isParsing = false,
                        refusalRes = refusalResFor(result.reason),
                        // 模型自己那句话是原因本体（"没收到动作库"之类），比我们能猜的诊断准。
                        refusalAnalysis = result.analysis,
                        notes = result.notes,
                        newExercises = result.newExercises.map { entry -> NewExerciseCandidate(entry) },
                        newFoods = result.newFoods.map { entry -> entry.toCandidate() },
                    )
                }

                is ExternalPlanImport.NothingAdoptable -> _uiState.update { state ->
                    state.copy(
                        isParsing = false,
                        refusalRes = null,
                        // 这条必须报数并说清是"被你自己的改动挡住"，不能只跳一页空预览。
                        snackbarRes = R.string.ai_import_nothing_adoptable,
                        snackbarArgs = listOf(result.preview.preservedCount.toString()),
                    )
                }

                is ExternalPlanImport.Ready -> {
                    if (result.newExercises.isEmpty() && result.newFoods.isEmpty()) {
                        handToPreview(result)
                    } else {
                        // ⚠️ 有没建的动作/食物时**不跳页**：跳过去就把用户留在预览页，
                        // 而那块「加入动作库 / 加入食物库」在已经关掉的弹层里 —— 界面会指着一块不在屏幕上的 UI。
                        // 留在弹层里让他选：建完自动重解析，或者"先只导入能导的"。
                        pending = result
                        _uiState.update { state ->
                            state.copy(
                                isParsing = false,
                                refusalRes = null,
                                refusalAnalysis = null,
                                notes = result.notes,
                                newExercises = result.newExercises.map { entry -> NewExerciseCandidate(entry) },
                                newFoods = result.newFoods.map { entry -> entry.toCandidate() },
                                canProceedWithoutThem = true,
                            )
                        }
                    }
                }
            }
        }
    }

    /** 把解析好的草案交给预览页（两条路共用：无候选时直接跳，"先只导入能导的"也走这里）。 */
    private fun handToPreview(result: ExternalPlanImport.Ready) {
        planPreviewHolder.set(
            preview = result.preview,
            importNotes = result.notes,
            profileDiffs = result.profileDiffs,
            reasons = result.reasons,
            dietDrafts = result.meals,
            mealSlots = result.mealSlots,
        )
        pending = null
        _uiState.update { state ->
            state.copy(
                isParsing = false,
                sheetOpen = false,
                text = "",
                refusalRes = null,
                refusalAnalysis = null,
                notes = emptyList(),
                newExercises = emptyList(),
                newFoods = emptyList(),
                canProceedWithoutThem = false,
                previewRequested = true,
            )
        }
    }

    /** 「先只导入能导的」：不建那些新动作 / 新食物，直接带着能导的部分去预览页。 */
    fun proceedWithoutCandidates() {
        pending?.let(::handToPreview)
    }

    /** 勾一个待新建的动作。 */
    fun onToggleNewExercise(index: Int) {
        _uiState.update { state ->
            val rows = state.newExercises.toMutableList()
            if (index in rows.indices) rows[index] = rows[index].copy(checked = !rows[index].checked)
            state.copy(newExercises = rows)
        }
    }

    /**
     * 把勾了的新动作建进库，然后**自动重解析一次**。
     *
     * 一个意图不该让用户点两次：建这些动作的目的就是让那些条目能导进来，
     * 停在"已加入，请再点一次解析"等于把半成品状态甩回给用户。
     */
    fun createSelectedExercisesAndReparse() {
        if (_uiState.value.isCreating) return
        val chosen: List<ImportedNewExercise> = _uiState.value.newExercises
            .filter { row -> row.checked }
            .map { row -> row.exercise }
        if (chosen.isEmpty()) return

        _uiState.update { it.copy(isCreating = true) }
        viewModelScope.launch {
            val created: Int = createExercises(chosen)
            _uiState.update { state ->
                state.copy(
                    isCreating = false,
                    newExercises = emptyList(),
                    snackbarRes = if (created > 0) R.string.ai_import_exercises_created else null,
                    snackbarArgs = listOf(created.toString()),
                )
            }
            parse()
        }
    }

    /** 勾一格待新建的食物（数值没填齐的行不给勾，见 [NewFoodCandidate.canBuild]）。 */
    fun onToggleNewFood(index: Int) {
        _uiState.update { state ->
            val rows = state.newFoods.toMutableList()
            if (index in rows.indices) rows[index] = rows[index].copy(checked = !rows[index].checked)
            state.copy(newFoods = rows)
        }
    }

    /** 「改」展开 / 收起。 */
    fun onToggleNewFoodEditing(index: Int) {
        _uiState.update { state ->
            val rows = state.newFoods.toMutableList()
            if (index in rows.indices) rows[index] = rows[index].copy(isEditing = !rows[index].isEditing)
            state.copy(newFoods = rows)
        }
    }

    /** 建库表单里某一格改了字。 */
    fun onNewFoodFieldChange(index: Int, field: ExternalImportViewModel.NewFoodField, value: String) {
        _uiState.update { state ->
            val rows = state.newFoods.toMutableList()
            if (index in rows.indices) {
                rows[index] = when (field) {
                    ExternalImportViewModel.NewFoodField.NAME -> rows[index].copy(name = value)
                    ExternalImportViewModel.NewFoodField.KCAL -> rows[index].copy(kcal = value)
                    ExternalImportViewModel.NewFoodField.PROTEIN -> rows[index].copy(protein = value)
                    ExternalImportViewModel.NewFoodField.CARBS -> rows[index].copy(carbs = value)
                    ExternalImportViewModel.NewFoodField.FAT -> rows[index].copy(fat = value)
                }
            }
            state.copy(newFoods = rows)
        }
    }

    /** 忌口标签勾 / 取消（非内置条目才有这一格 —— 内置的标签在食物库里也不给改）。 */
    fun onToggleNewFoodTag(index: Int, tag: DietRestriction) {
        _uiState.update { state ->
            val rows = state.newFoods.toMutableList()
            if (index in rows.indices) {
                val tags = rows[index].dietaryTags.toMutableSet()
                if (!tags.add(tag)) tags.remove(tag)
                rows[index] = rows[index].copy(dietaryTags = tags)
            }
            state.copy(newFoods = rows)
        }
    }

    /**
     * 把勾了、也填齐了的食物建进食物库，然后**自动重解析一次**（与动作侧同一套机制）。
     *
     * 重解析是这一步的一部分而不是"下一步"：用户建这些食物的目的就是让那一餐算得出来，
     * 停在"已加入，请再点一次解析"等于把半成品甩回给他。第二次解析时这些名字"库里已有"，
     * **必须静默通过**（刀 4 从真机学来的教训）。
     */
    fun createSelectedFoodsAndReparse() {
        if (_uiState.value.isCreating) return
        val chosen: List<ImportedNewFood> = _uiState.value.newFoods.mapNotNull { row -> row.toNewFood() }
        if (chosen.isEmpty()) return

        _uiState.update { it.copy(isCreating = true) }
        viewModelScope.launch {
            val created: Int = createFoods(chosen)
            _uiState.update { state ->
                state.copy(
                    isCreating = false,
                    newFoods = emptyList(),
                    snackbarRes = if (created > 0) R.string.ai_import_foods_created else null,
                    snackbarArgs = listOf(created.toString()),
                )
            }
            parse()
        }
    }

    fun onPreviewConsumed() {
        _uiState.update { it.copy(previewRequested = false) }
    }

    fun onSnackbarShown() {
        _uiState.update { it.copy(snackbarRes = null, snackbarArgs = emptyList()) }
    }

    /**
     * 一态一句人话。
     *
     * ⚠️ **穷尽匹配、不写 else**：将来加第六种拒收时，这里要编译不过，
     * 而不是悄悄复用一句万能的"格式错误" —— 那等于把"下一步该干什么"又交回给用户猜。
     */
    @StringRes
    private fun refusalResFor(reason: ExternalDocRefusal): Int = when (reason) {
        ExternalDocRefusal.EMPTY_DOCUMENT -> R.string.ai_import_refuse_empty
        ExternalDocRefusal.NOT_A_DOCUMENT -> R.string.ai_import_refuse_not_json
        ExternalDocRefusal.WRONG_SCHEMA -> R.string.ai_import_refuse_wrong_schema
        ExternalDocRefusal.TOO_LARGE -> R.string.ai_import_refuse_too_large
        ExternalDocRefusal.NOTHING_TO_IMPORT -> R.string.ai_import_refuse_nothing_to_import
        ExternalDocRefusal.NO_USABLE_ITEMS -> R.string.ai_import_refuse_no_items
    }
}
