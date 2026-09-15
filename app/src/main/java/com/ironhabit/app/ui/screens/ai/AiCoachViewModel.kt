package com.ironhabit.app.ui.screens.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.ExerciseSuggestion
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.PlanNote
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
import com.ironhabit.app.domain.usecase.SuggestExercisesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一次「生成计划」的结果（面向 UI 的纯展示数据）。 */
data class PlanResultUi(
    /** 实际写入本周计划的条数。 */
    val writtenCount: Int = 0,
    /** 被完整保留的用户手改条数（**未被覆盖**）。 */
    val preservedCount: Int = 0,
    /** 「为什么这样排」的理由列表。 */
    val notes: List<PlanNote> = emptyList(),
)

/**
 * 「AI 教练」UI 状态（不可变）。
 *
 * @property isLoading 首帧加载中
 * @property profile 用户档案（只读展示 + 规则输入）
 * @property currentWeightKg 当前体重（只读，来自 `body_metrics` 最新 WEIGHT 值；无记录为 `null`）
 * @property planResult 最近一次「生成计划」的结果（`null` = 尚未生成过）
 * @property isGenerating 生成计划进行中（本地规则为纯计算，通常很快）
 * @property suggestions 补充动作建议（已排除动作库中已有的）
 * @property adoptedNames 本次会话已收入的动名称（幂等：重复点击不再写入）
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性提示资源 id
 * @property snackbarArgs 提示的格式化参数
 */
data class AiCoachUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile = UserProfile(),
    val currentWeightKg: Float? = null,
    val planResult: PlanResultUi? = null,
    val isGenerating: Boolean = false,
    val suggestions: List<ExerciseSuggestion> = emptyList(),
    val adoptedNames: Set<String> = emptySet(),
    @StringRes val errorRes: Int? = null,
    @StringRes val snackbarRes: Int? = null,
    val snackbarArgs: List<String> = emptyList(),
)

/**
 * 「AI 教练」ViewModel（**本地规则版 · 完全离线**）。
 *
 * 职责：
 * - 暴露档案流与最新体重（用于「教练解读」的 BMR / 建议摄入）；
 * - 调 [GenerateTrainingPlanUseCase] 生成计划（**手改行由 UseCase 保证不被覆盖**）；
 * - 调 [SuggestExercisesUseCase] 取补充动作建议并支持「一键收入」（幂等由 UseCase 保证）。
 *
 * ⚠️ **诚实原则**：本页一律使用本地规则，**不含任何联网调用**；文案不得出现
 * "模型 / 智能生成 / AI 分析"等暗示云端的措辞。
 */
@HiltViewModel
class AiCoachViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    bodyMetricRepository: BodyMetricRepository,
    private val generateTrainingPlan: GenerateTrainingPlanUseCase,
    private val suggestExercises: SuggestExercisesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiCoachUiState())
    val uiState: StateFlow<AiCoachUiState> = _uiState.asStateFlow()

    /** 最新体重（`body_metrics` 为体重唯一真源，档案不存体重）。 */
    private val latestWeightKg = bodyMetricRepository
        .observeByType(BodyMetricType.WEIGHT)
        .map { it.firstOrNull()?.value }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.profile(),
                latestWeightKg,
            ) { profile: UserProfile, weight: Float? -> profile to weight }
                .collect { (profile, weight) ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            profile = profile,
                            currentWeightKg = weight,
                        )
                    }
                }
        }
        loadSuggestions()
    }

    /** 生成 / 重新生成训练计划（写入本周计划，用户手改行保持不动）。 */
    fun generatePlan() {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, errorRes = null) }
            runCatching { generateTrainingPlan() }
                .onSuccess { summary ->
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            planResult = PlanResultUi(
                                writtenCount = summary.writtenCount,
                                preservedCount = summary.preservedCount,
                                notes = summary.notes,
                            ),
                            snackbarRes = R.string.ai_plan_written_hint,
                            snackbarArgs = listOf(summary.writtenCount.toString()),
                        )
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            errorRes = R.string.error_save_failed,
                        )
                    }
                }
        }
    }

    /** 刷新补充动作建议（已在动作库中的不会出现在结果里）。 */
    fun loadSuggestions() {
        viewModelScope.launch {
            runCatching { suggestExercises.suggest() }
                .onSuccess { list -> _uiState.update { it.copy(suggestions = list) } }
                .onFailure { _uiState.update { it.copy(errorRes = R.string.error_save_failed) } }
        }
    }

    /**
     * 收入一条补充动作。
     *
     * **幂等**：已在动作库 / 本次会话已收入的，直接提示"已存在"，**不重复写入**。
     */
    fun adopt(name: String) {
        viewModelScope.launch {
            runCatching { suggestExercises.adopt(name) }
                .onSuccess { result ->
                    val messageRes = when (result) {
                        AdoptResult.ADDED -> R.string.ai_suggest_adopted
                        AdoptResult.ALREADY_EXISTS -> R.string.ai_suggest_already_exists
                    }
                    _uiState.update {
                        it.copy(
                            adoptedNames = it.adoptedNames + name,
                            snackbarRes = messageRes,
                            snackbarArgs = emptyList(),
                        )
                    }
                    loadSuggestions()
                }
                .onFailure { _uiState.update { it.copy(errorRes = R.string.error_save_failed) } }
        }
    }

    /** 提示已展示，清空一次性消息。 */
    fun onSnackbarShown() {
        _uiState.update { it.copy(snackbarRes = null, snackbarArgs = emptyList()) }
    }

    /**
     * 基础代谢估算（Mifflin-St Jeor），**纯本地计算**。
     *
     * @return `null` 表示信息不足（无体重记录 / 体征未填全）→ UI 显示"补全体征与体重后显示"。
     */
    fun estimateBmr(): Int? {
        val state = _uiState.value
        val profile = state.profile
        val weight = state.currentWeightKg ?: return null
        if (!profile.isBodyProfileComplete) return null
        val age = profile.age ?: return null
        val height = profile.heightCm ?: return null
        val base = 10.0 * weight + 6.25 * height - 5.0 * age
        return (base + if (profile.gender == Gender.MALE) 5.0 else -161.0).toInt()
    }
}
