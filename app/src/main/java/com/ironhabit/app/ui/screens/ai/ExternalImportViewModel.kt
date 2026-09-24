package com.ironhabit.app.ui.screens.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.ai.external.ExternalDocRefusal
import com.ironhabit.app.domain.ai.external.ExternalPlanNote
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.usecase.BuildExternalCoachPromptUseCase
import com.ironhabit.app.domain.usecase.ExternalPlanImport
import com.ironhabit.app.domain.usecase.ImportExternalPlanUseCase
import com.ironhabit.app.domain.usecase.PlanPreviewHolder
import com.ironhabit.app.domain.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
 * ## 三条诚实约束（每条都对应界面上一句话）
 * 1. 模板由本地数据拼出来，不谎称"AI 已经帮你排好"；
 * 2. 解析丢掉的每一条都要摊在预览页上（[ExternalPlanNote]），不许静默少写；
 * 3. 导入的行算「AI 生成的行」，预览页必须写明"以后重新生成会被覆盖"。
 */
@HiltViewModel
class ExternalImportViewModel @Inject constructor(
    private val buildPromptTemplate: BuildExternalCoachPromptUseCase,
    private val importPlan: ImportExternalPlanUseCase,
    private val planPreviewHolder: PlanPreviewHolder,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    /** 只给两个选项：「每周相同」模板（`week_start = 0`）和已经过完的周都不作为落点。 */
    enum class WeekChoice { THIS_WEEK, NEXT_WEEK }

    data class UiState(
        val sheetOpen: Boolean = false,
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
        /** 逐条"什么没进来 / 什么被动过"。拒收时也可能非空（全被挡掉那一种）。 */
        val notes: List<ExternalPlanNote> = emptyList(),
        @StringRes val snackbarRes: Int? = null,
        val snackbarArgs: List<Any> = emptyList(),
        /** 草案已放进 holder → 界面跳一次预览页。 */
        val previewRequested: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 选定那一周的周一 epochDay（界面用它算「本周 9/21–9/27」这类标签）。 */
    fun weekStartEpochDay(choice: WeekChoice = _uiState.value.week): Long {
        val today: LocalDate = clock.now().toLocalDateTime(timeZone).date
        val thisMonday: Long = DateUtils.weekStartMon1(today.toEpochDays().toLong())
        return if (choice == WeekChoice.THIS_WEEK) thisMonday else thisMonday + 7L
    }

    fun open() {
        _uiState.update { it.copy(sheetOpen = true) }
    }

    /** 关闭时把模板与拒收说明一起清掉：下次进来不能还挂着上一次的结论。 */
    fun dismiss() {
        _uiState.update {
            it.copy(
                sheetOpen = false,
                template = null,
                isBuildingTemplate = false,
                templateFailedRes = null,
                refusalRes = null,
                notes = emptyList(),
            )
        }
    }

    /** 换周 → 模板必须重算：模板里"这一周已经排了什么"那一段是按周拼的。 */
    fun onWeekChange(choice: WeekChoice) {
        _uiState.update { it.copy(week = choice, template = null, templateFailedRes = null) }
    }

    fun onTextChange(text: String) {
        _uiState.update { it.copy(text = text, refusalRes = null, notes = emptyList()) }
    }

    /** 「读取剪贴板」按下的结果由 UI 层传进来（剪贴板只能从 Compose 侧读）。 */
    fun onClipboardRead(clipboardText: String?) {
        if (clipboardText.isNullOrBlank()) {
            _uiState.update { it.copy(snackbarRes = R.string.ai_import_clipboard_empty) }
            return
        }
        _uiState.update { it.copy(text = clipboardText, refusalRes = null, notes = emptyList()) }
    }

    /**
     * 拼提问模板（复盘从页面状态传进来：教练页已经算过一份，这里绝不重算一次）。
     *
     * 复盘还没算出来时不给结果也不报错 —— 按钮此刻是禁用的，界面上写的是"正在整理你的数据…"。
     */
    fun buildTemplate(review: WeeklyReview?) {
        if (review == null || _uiState.value.isBuildingTemplate) return
        val week: Long = weekStartEpochDay()
        _uiState.update { it.copy(isBuildingTemplate = true, templateFailedRes = null) }
        viewModelScope.launch {
            val text: String? = try {
                buildPromptTemplate(review, week)
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
        _uiState.update { it.copy(isParsing = true, refusalRes = null, notes = emptyList()) }

        viewModelScope.launch {
            val result: ExternalPlanImport = importPlan(text, weekStart)

            when (result) {
                is ExternalPlanImport.Refused -> _uiState.update {
                    it.copy(
                        isParsing = false,
                        refusalRes = refusalResFor(result.reason),
                        notes = result.notes,
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
                    planPreviewHolder.set(result.preview, result.notes)
                    _uiState.update { state ->
                        state.copy(
                            isParsing = false,
                            sheetOpen = false,
                            text = "",
                            refusalRes = null,
                            notes = emptyList(),
                            previewRequested = true,
                        )
                    }
                }
            }
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
        ExternalDocRefusal.NO_USABLE_ITEMS -> R.string.ai_import_refuse_no_items
    }
}
