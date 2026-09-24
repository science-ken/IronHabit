package com.ironhabit.app.ui.screens.planpreview

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.ai.external.ExternalPlanNote
import com.ironhabit.app.domain.ai.external.ProfileFieldDiff
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.usecase.ApplyExternalProfileUseCase
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
import com.ironhabit.app.domain.usecase.PlanPreview
import com.ironhabit.app.domain.usecase.PlanPreviewHolder
import com.ironhabit.app.ui.components.monthDayText
import com.ironhabit.app.ui.components.weekRangeText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「本周计划预览」页：把 [PlanPreview] 摊开给用户看，**逐天**决定要不要写进库。
 *
 * 页面本身不持有任何数据库状态 —— 采纳走 [GenerateTrainingPlanUseCase.commit]
 * （只写被点的那几天），取消只是清掉快照。
 */
@HiltViewModel
class PlanPreviewViewModel @Inject constructor(
    private val generateTrainingPlan: GenerateTrainingPlanUseCase,
    private val holder: PlanPreviewHolder,
    private val exerciseRepository: ExerciseRepository,
    private val applyProfile: ApplyExternalProfileUseCase,
) : ViewModel() {

    /** 一条草案的展示形态（动作名已经从库里查好了）。 */
    data class Item(val name: String, val goal: String)

    /**
     * 一行档案改动 + 用户勾没勾。
     *
     * **默认不勾**：文档"想改"不等于用户"同意改"，何况这一勾是立即写进档案、不给撤销。
     */
    data class ProfileRow(val diff: ProfileFieldDiff, val checked: Boolean = false)

    /** 这一天在预览里的身份。 */
    enum class Kind {
        /** 有草案，可以采纳。 */
        DRAFT,

        /** 这天由「每周相同」那份（含用户手改）负责，本次整日不动。 */
        TEMPLATE_OWNED,

        /** 没排课 —— 休息日。 */
        REST,
    }

    data class Day(
        val dayOfWeek: Int,
        val dateLabel: String,
        val items: List<Item>,
        val sets: Int,
        val kind: Kind,
        val adopted: Boolean = false,
    )

    data class UiState(
        val weekRange: String = "",
        /**
         * 本次草案的来源，原样带下来给界面做**穷尽**匹配。
         *
         * 以前是 `sourceIsAi: Boolean`：布尔只能说"是 AI / 不是 AI"，而「外部 AI 导入」
         * 既不是 app 联网生成的、也不是本地规则 —— 用布尔它就只能被塞进"本地规则"那一格。
         */
        val source: AdviceSource = AdviceSource.LOCAL_RULES,
        /** 本次是"想用 AI 但失败了才落到本地规则"，不是"用户本来就关着 AI"。 */
        val fellBackFromRemote: Boolean = false,
        val days: List<Day> = emptyList(),
        /**
         * 外部 AI 文档导入时"哪些条目没进来、为什么"（内置生成恒为空 → 那一块整块不显示）。
         *
         * 摊在**采纳之前**而不是之后：用户点"采纳这天"之后才发现少了两条，已经来不及知道少了什么。
         */
        val importNotes: List<ExternalPlanNote> = emptyList(),
        /** 这份文档还想改的档案项（逐字段勾选）。内置生成路恒为空 → 那一块整块不显示。 */
        val profileRows: List<ProfileRow> = emptyList(),
        val preservedCount: Int = 0,
        /**
         * 这次写好的「为什么这么排」（远端与外部导入都有；本地规则恒为 `null`，
         * 因为 `LocalRuleAdvisor` 只吐资源名、不产中文）。
         */
        val analysis: String? = null,
        val busy: Boolean = false,
        @StringRes val snackbarRes: Int? = null,
        val snackbarArg: String? = null,
        /** 采纳完 / 取消完 → 界面收到该信号就返回上一页。 */
        val finished: Boolean = false,
    ) {
        val draftDays: List<Day> get() = days.filter { day -> day.kind == Kind.DRAFT }
        val adoptedDays: Int get() = draftDays.count { day -> day.adopted }
        val pendingDays: Int get() = draftDays.size - adoptedDays
        val adoptedItemCount: Int get() = draftDays.filter { day -> day.adopted }.sumOf { day -> day.items.size }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var preview: PlanPreview? = null

    init {
        load()
    }

    private fun load() {
        val snapshot: PlanPreview = holder.peek() ?: return
        preview = snapshot
        viewModelScope.launch {
            val names: Map<Long, String> = exerciseRepository.observeActive().first()
                .associate { exercise -> exercise.id to exercise.name }
            _uiState.update { state ->
                state.copy(
                    weekRange = weekRangeText(snapshot.weekStartEpochDay),
                    importNotes = holder.peekImportNotes(),
                    profileRows = holder.peekProfileDiffs().map { diff -> ProfileRow(diff) },
                    source = snapshot.source,
                    fellBackFromRemote = snapshot.fallbackReason != null,
                    analysis = snapshot.analysis,
                    preservedCount = snapshot.preservedCount,
                    days = (MIN_DAY..MAX_DAY).map { day ->
                        val rows: List<WeekPlan>? = snapshot.draftsByDay[day]
                        when {
                            rows != null -> Day(
                                dayOfWeek = day,
                                dateLabel = monthDayText(snapshot.weekStartEpochDay + day - 1),
                                sets = rows.sumOf { row -> row.targetSets },
                                items = rows.map { row -> Item(name = names[row.exerciseId] ?: "-", goal = goalOf(row)) },
                                kind = Kind.DRAFT,
                            )

                            day in snapshot.templateOwnedDays -> restDay(snapshot, day, Kind.TEMPLATE_OWNED)
                            else -> restDay(snapshot, day, Kind.REST)
                        }
                    },
                )
            }
        }
    }

    private fun restDay(snapshot: PlanPreview, day: Int, kind: Kind) = Day(
        dayOfWeek = day,
        dateLabel = monthDayText(snapshot.weekStartEpochDay + day - 1),
        items = emptyList(),
        sets = 0,
        kind = kind,
    )

    fun onAdoptDay(day: Int) = commit(setOf(day))

    fun onAdoptAll() {
        val pending: Set<Int> = _uiState.value.days
            .filter { day -> day.kind == Kind.DRAFT && !day.adopted }
            .map { day -> day.dayOfWeek }
            .toSet()
        if (pending.isEmpty()) return
        commit(pending)
    }

    fun onCancel() {
        holder.clear()
        _uiState.update { state -> state.copy(finished = true) }
    }

    fun onConsumeSnackbar() = _uiState.update { state -> state.copy(snackbarRes = null, snackbarArg = null) }

    private fun commit(days: Set<Int>) {
        val snapshot: PlanPreview = preview ?: return
        if (_uiState.value.busy) return
        _uiState.update { state -> state.copy(busy = true) }
        viewModelScope.launch {
            val summary = generateTrainingPlan.commit(snapshot, days)
            if (summary.writtenCount == 0) {
                _uiState.update { state -> state.copy(busy = false, snackbarRes = R.string.msg_plan_nothing_adoptable) }
                return@launch
            }
            _uiState.update { state ->
                val next = state.days.map { day ->
                    if (day.dayOfWeek in days && day.kind == Kind.DRAFT) day.copy(adopted = true) else day
                }
                state.copy(
                    busy = false,
                    days = next,
                    snackbarRes = R.string.msg_plan_adopted_count,
                    snackbarArg = summary.writtenCount.toString(),
                )
            }
            // 草案全部采纳完就把快照丢掉，避免下次进来看到上一轮的陈旧内容。
            if (_uiState.value.pendingDays == 0) holder.clear()
        }
    }

    /** 勾某一行档案改动。 */
    fun onToggleProfileField(index: Int) {
        _uiState.update { state ->
            val rows = state.profileRows.toMutableList()
            if (index in rows.indices) rows[index] = rows[index].copy(checked = !rows[index].checked)
            state.copy(profileRows = rows)
        }
    }

    /**
     * 应用**勾了的**那几项档案改动：立即逐字段写、不给撤销。
     *
     * 时机与「采纳这天」一致（点一下就进库，反悔要自己去「我的」页改回来）。
     * 混在一个"整页确认"的按钮里反而更糟：档案和计划是两件事，一次点击同时改两张表，
     * 用户没法说清自己刚才同意了哪一个。
     */
    fun onApplyProfile() {
        if (_uiState.value.busy) return
        val checked: List<ProfileFieldDiff> = _uiState.value.profileRows
            .filter { row -> row.checked }
            .map { row -> row.diff }
        if (checked.isEmpty()) return

        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val applied: Int = applyProfile(checked)
            _uiState.update { state ->
                state.copy(
                    busy = false,
                    // 改完就把那几行撤下清单：留着它们，第二次点「应用」会把同一批值再写一遍，
                    // 而界面看起来什么都没发生。
                    profileRows = state.profileRows.filterNot { row -> row.checked },
                    snackbarRes = R.string.plan_preview_profile_applied,
                    snackbarArg = applied.toString(),
                )
            }
        }
    }

    /** 「3 × 12 · 20kg」「4 × 45′」这类目标文案；重量单位沿用全 app 现状（写死 kg）。 */
    private fun goalOf(row: WeekPlan): String = buildString {
        append(row.targetSets)
        append(" × ")
        append(row.targetReps)
        row.targetWeightKg?.let { weight ->
            append(" · ")
            append(weight.toInt())
            append("kg")
        }
        row.targetDurationMin?.let { minutes ->
            append(" · ")
            append(minutes)
            append("′")
        }
    }

    private companion object {
        const val MIN_DAY = 1
        const val MAX_DAY = 7
    }
}
