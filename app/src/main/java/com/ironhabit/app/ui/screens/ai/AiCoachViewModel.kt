package com.ironhabit.app.ui.screens.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.model.AdoptResult
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.ExerciseSuggestion
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.PlanBasisItem
import com.ironhabit.app.domain.model.PlanNote
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.AskCoachUseCase
import com.ironhabit.app.domain.usecase.CoachAnswer
import com.ironhabit.app.domain.usecase.ExplainDietUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
import com.ironhabit.app.domain.usecase.SuggestExercisesUseCase
import com.ironhabit.app.domain.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/** 一次「生成计划」的结果（面向 UI 的纯展示数据）。 */
data class PlanResultUi(
    /** 实际写入本周计划的条数。 */
    val writtenCount: Int = 0,
    /** 被完整保留的用户手改条数（**未被覆盖**）。 */
    val preservedCount: Int = 0,
    /** 本次被回收的陈旧 AI 行条数（上版生成、本次不再出现 → 已停用，修复 C2）。 */
    val retiredCount: Int = 0,
    /** 「为什么这样排」的理由列表。 */
    val notes: List<PlanNote> = emptyList(),
    /** 本次实际来源（本地规则 / AI 联网）——诚实标注，不许 UI 猜。 */
    val source: AdviceSource = AdviceSource.LOCAL_RULES,
    /** 走本地时的回落原因（`null` = 没有回落）。 */
    val fallbackReason: RemoteFallbackReason? = null,
    /** 本次写入的计划条目（含 星期/组数/次数/重量），供 UI 卡片化展示。 */
    val plans: List<WeekPlan> = emptyList(),
    /** 远端 AI 返回的自由文本分析（仅 REMOTE_LLM 有值；本地规则恒为 null）。 */
    val analysis: String? = null,
    /** 本地规则的「生成依据」要点（结构化）。 */
    val basis: List<PlanBasisItem> = emptyList(),
)

/**
 * 一次「生成饮食计划」的结果（面向 UI 的纯展示数据，子项 B）。
 *
 * ⚠️ **数值全部来自本地纯函数**（[com.ironhabit.app.domain.diet.DietPlanGenerator]），
 * 远端 AI 只提供文字分析，不参与任何数值计算。
 *
 * @property writtenCount 本次实际写入的餐数
 * @property preservedCount 被完整保留的用户手改餐数（含软删行）
 * @property targetKcal 本地目标热量
 * @property targetProtein 本地目标蛋白质
 * @property usedDefaults 是否用了默认目标值（档案/体重未填全）
 * @property filteredCount 因忌口被过滤掉的食物条目数
 */
data class DietSummaryUi(
    val writtenCount: Int = 0,
    val preservedCount: Int = 0,
    val targetKcal: Int = 0,
    val targetProtein: Int = 0,
    val usedDefaults: Boolean = false,
    val filteredCount: Int = 0,
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
 * @property exerciseNames 动作 id → 名称（用于把"为什么这样排"里的 id 显示成动作名）
 * @property chatMessages 「问教练」最近若干轮消息（**只在内存里，问答不落库**）
 * @property chatInput 「问教练」输入框当前内容
 * @property isAsking 正在等 AI 回答（发送中：输入框与按钮都禁用）
 * @property dietSummary 最近一次「生成饮食」的本地结果（`null` = 本次会话尚未生成过）
 * @property isGeneratingDiet 生成饮食进行中
 * @property dietAnalysis 远端 AI 的「为什么这样吃」分析；`null` = 未联网 / 失败（此时 UI 显示本地依据卡）
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性提示资源 id
 * @property snackbarArgs 提示的格式化参数（**类型必须与资源占位符一致**：`%1$d` 传 Int、`%1$s` 传 String；
 *   传错类型会在 `stringResource` 格式化时抛 `IllegalFormatConversionException` 直接崩溃）
 */
data class AiCoachUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile = UserProfile(),
    val currentWeightKg: Float? = null,
    val planResult: PlanResultUi? = null,
    val isGenerating: Boolean = false,
    val suggestions: List<ExerciseSuggestion> = emptyList(),
    val adoptedNames: Set<String> = emptySet(),
    val exerciseNames: Map<Long, String> = emptyMap(),
    /** 最近一次建议结果的来源（本地规则 / AI 联网）。 */
    val suggestionSource: AdviceSource = AdviceSource.LOCAL_RULES,
    /** 建议走本地时的回落原因（`null` = 没有回落）。 */
    val suggestionFallbackReason: RemoteFallbackReason? = null,
    /** 「AI 联网增强」开关（默认关）。 */
    val aiRemoteEnabled: Boolean = false,
    /** 是否已配置 API Key（快照；加密文件无响应式流，写入后由 ViewModel 手动刷新）。 */
    val hasApiKey: Boolean = false,
    /** 「问教练」最近若干轮消息（**内存态**：问答不落库，离开页面即丢弃）。 */
    val chatMessages: List<CoachChatMessage> = emptyList(),
    /** 「问教练」输入框内容（内存态）。 */
    val chatInput: String = "",
    /** 正在等 AI 回答：发送中禁用输入框与按钮，避免并发发问。 */
    val isAsking: Boolean = false,
    /** 最近一次「生成饮食」的**本地**结果（`null` = 本次会话尚未生成过）。 */
    val dietSummary: DietSummaryUi? = null,
    /** 生成饮食进行中（本地生成本身很快，联网分析会稍慢，二者用同一标志）。 */
    val isGeneratingDiet: Boolean = false,
    /** 远端 AI 的「为什么这样吃」分析；`null` = 未联网 / 失败 → UI 显示本地依据卡。 */
    val dietAnalysis: String? = null,
    @StringRes val errorRes: Int? = null,
    @StringRes val snackbarRes: Int? = null,
    val snackbarArgs: List<Any> = emptyList(),
) {

    /**
     * 是否具备「问教练」的联网条件：**开关已开 且 已配 Key**。
     *
     * UI 只认这一个派生值（不要在页面里各写一遍 `aiRemoteEnabled && hasApiKey`）：
     * `false` → 显示诚实禁用说明、不渲染输入框；`true` → 可用。
     */
    val canAskCoach: Boolean
        get() = aiRemoteEnabled && hasApiKey
}

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
    private val settingsRepository: SettingsRepository,
    bodyMetricRepository: BodyMetricRepository,
    exerciseRepository: ExerciseRepository,
    private val aiCredentialsStore: AiCredentialsStore,
    private val generateTrainingPlan: GenerateTrainingPlanUseCase,
    private val suggestExercises: SuggestExercisesUseCase,
    private val askCoach: AskCoachUseCase,
    private val generateDietPlan: GenerateDietPlanUseCase,
    private val explainDiet: ExplainDietUseCase,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiCoachUiState())
    val uiState: StateFlow<AiCoachUiState> = _uiState.asStateFlow()

    /**
     * 最近一次失败的动作（供 [onRetry] 决定重跑哪一个）。
     *
     * 私有字段：只影响「重试跑什么」，不进 [AiCoachUiState]，界面文案一律走资源 id。
     * 初值取「加载建议」——页面首帧本来就会拉一次建议。
     */
    private var lastFailedAction: FailedAction = FailedAction.LOAD_SUGGESTIONS

    /** 最新体重（`body_metrics` 为体重唯一真源，档案不存体重）。 */
    private val latestWeightKg = bodyMetricRepository
        .observeByType(BodyMetricType.WEIGHT)
        .map { it.firstOrNull()?.value }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.profile(),
                settingsRepository.aiRemoteEnabled(),
                latestWeightKg,
            ) { profile: UserProfile, aiRemote: Boolean, weight: Float? ->
                Triple(profile, aiRemote, weight)
            }.collect { (profile, aiRemote, weight) ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        profile = profile,
                        currentWeightKg = weight,
                        aiRemoteEnabled = aiRemote,
                        hasApiKey = aiCredentialsStore.isConfigured(),
                    )
                }
            }
        }
        loadSuggestions()
        viewModelScope.launch {
            exerciseRepository.observeActive().collect { exercises ->
                _uiState.update { it.copy(exerciseNames = exercises.associate { e -> e.id to e.name }) }
            }
        }
    }

    /**
     * 刷新「是否已配置 Key」快照。
     *
     * 加密文件无响应式流：用户在设置页保存/清除 Key 后回到本页时，
     * DataStore 流不会重发，必须由 UI 在 ON_RESUME 主动调此方法（否则徽标滞留旧状态）。
     */
    fun refreshKeyStatus() {
        _uiState.update { it.copy(hasApiKey = aiCredentialsStore.isConfigured()) }
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
                                retiredCount = summary.retiredCount,
                                notes = summary.notes,
                                source = summary.source,
                                fallbackReason = summary.fallbackReason,
                                plans = summary.plans,
                                analysis = summary.analysis,
                                basis = summary.basis,
                            ),
                            snackbarRes = R.string.ai_plan_written_hint,
                            snackbarArgs = listOf(summary.writtenCount),
                        )
                    }
                }
                .onFailure {
                    lastFailedAction = FailedAction.GENERATE_PLAN
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            errorRes = R.string.error_save_failed,
                        )
                    }
                }
        }
    }

    /**
     * 生成 / 重新生成**今日饮食计划**（子项 B）。
     *
     * 分工（**数值一律以本地为准**）：
     * 1. 先跑本地 [GenerateDietPlanUseCase]（写入今日餐次；用户手改餐不被覆盖，红线）；
     * 2. 本地写入成功后再尝试远端「为什么这样吃」分析 —— 未联网 / 失败都不影响第 1 步结果，
     *    UI 自动退回本地「生成依据」卡（不弹错、不阻断）。
     *
     * 提示优先级（与 `TodayViewModel.onGenerateDiet` 同口径）：忌口过滤 > 用了默认目标值 > 生成成功。
     */
    fun generateDiet() {
        if (_uiState.value.isGeneratingDiet) return
        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingDiet = true, errorRes = null) }
            runCatching { generateDietPlan(todayEpochDay()) }
                .onSuccess { summary ->
                    val hintRes: Int
                    // ⚠️ `msg_diet_filtered` 的占位符是 %1$s（String 通道）→ 必须传 toString()。
                    //    历史上这里写成 %1$d 却收到 String，一勾忌口就 IllegalFormatConversionException 崩溃。
                    val hintArgs: List<Any>
                    when {
                        summary.filteredCount > 0 -> {
                            hintRes = R.string.msg_diet_filtered
                            hintArgs = listOf(summary.filteredCount.toString())
                        }

                        summary.target.usedDefaults -> {
                            hintRes = R.string.profile_incomplete_hint
                            hintArgs = emptyList()
                        }

                        else -> {
                            hintRes = R.string.msg_diet_generated
                            hintArgs = emptyList()
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isGeneratingDiet = false,
                            dietSummary = DietSummaryUi(
                                writtenCount = summary.writtenCount,
                                preservedCount = summary.preservedCount,
                                targetKcal = summary.target.targetKcal,
                                targetProtein = summary.target.targetProtein,
                                usedDefaults = summary.target.usedDefaults,
                                filteredCount = summary.filteredCount,
                            ),
                            // 每次重新生成都先清掉旧分析，避免「数值已变、解释还是上一版」。
                            dietAnalysis = null,
                            snackbarRes = hintRes,
                            snackbarArgs = hintArgs,
                        )
                    }
                    requestDietAnalysis(summary.target)
                }
                .onFailure {
                    lastFailedAction = FailedAction.GENERATE_DIET
                    _uiState.update {
                        it.copy(isGeneratingDiet = false, errorRes = R.string.error_save_failed)
                    }
                }
        }
    }

    /**
     * 远端「为什么这样吃」分析：**失败只让 [AiCoachUiState.dietAnalysis] 保持 `null`**，
     * 页面照常用本地的热量 / 蛋白质结果，不弹错、不阻断。
     */
    private suspend fun requestDietAnalysis(target: DietTarget) {
        val answer: CoachAnswer = try {
            explainDiet(target)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Exception) {
            CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR)
        }
        _uiState.update {
            when (answer) {
                is CoachAnswer.Ok -> it.copy(dietAnalysis = answer.text)
                is CoachAnswer.NeedsNetwork -> it.copy(dietAnalysis = null)
                is CoachAnswer.Failed -> it.copy(dietAnalysis = null)
            }
        }
    }

    /** 今天（本地时区）：饮食计划按「今天」生成。 */
    private fun todayEpochDay(): Long = DateUtils.todayEpochDay(clock, timeZone)

    /** 刷新补充动作建议（已在动作库中的不会出现在结果里）。 */
    fun loadSuggestions() {
        viewModelScope.launch {
            runCatching { suggestExercises.suggest() }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            suggestions = result.suggestions,
                            suggestionSource = result.source,
                            suggestionFallbackReason = result.fallbackReason,
                        )
                    }
                }
                .onFailure {
                    lastFailedAction = FailedAction.LOAD_SUGGESTIONS
                    _uiState.update { it.copy(errorRes = R.string.error_save_failed) }
                }
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
                .onFailure {
                    // 收入失败也按「重跑建议加载」处理：重试后列表回到与库一致的状态。
                    lastFailedAction = FailedAction.LOAD_SUGGESTIONS
                    _uiState.update { it.copy(errorRes = R.string.error_save_failed) }
                }
        }
    }

    /**
     * 重试上一次失败的动作。
     *
     * 失败不再静默（页面上有内联错误卡），用户点「重试」时回到这里：
     * 先清掉 [AiCoachUiState.errorRes]，再**重跑那个失败的动作** ——
     * 生成计划失败就重跑 [generatePlan]，其余（建议加载 / 收入失败）重跑 [loadSuggestions]。
     */
    fun onRetry() {
        val failed: FailedAction = lastFailedAction
        _uiState.update { it.copy(errorRes = null) }
        when (failed) {
            FailedAction.GENERATE_PLAN -> generatePlan()
            FailedAction.LOAD_SUGGESTIONS -> loadSuggestions()
            FailedAction.GENERATE_DIET -> generateDiet()
        }
    }

    /** 提示已展示，清空一次性消息。 */
    fun onSnackbarShown() {
        _uiState.update { it.copy(snackbarRes = null, snackbarArgs = emptyList()) }
    }

    /** 「问教练」输入框变化（纯内存态，问答不落库）。 */
    fun onChatInputChange(text: String) {
        _uiState.update { it.copy(chatInput = text) }
    }

    /**
     * 发送一条问题给 AI 教练（**问答不落库**：只在内存里保留最近 [MAX_CHAT_MESSAGES] 条消息）。
     *
     * 三态由 [AskCoachUseCase] 的**可识别结果**决定，UI 不做任何猜测：
     * - [CoachAnswer.Ok] → 正常回答气泡（内容来自 DeepSeek）；
     * - [CoachAnswer.NeedsNetwork] → 固定的「需要联网」气泡（**绝不本地编造回答冒充 AI**）；
     * - [CoachAnswer.Failed] → 固定的「联网失败」气泡，用户可以再问一次。
     *
     * 发送中（[AiCoachUiState.isAsking]）直接忽略重复调用，避免并发发问与消息乱序。
     */
    fun onAskCoach() {
        val snapshot: AiCoachUiState = _uiState.value
        if (snapshot.isAsking) return
        val question: String = snapshot.chatInput.trim()
        if (question.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    chatInput = "",
                    isAsking = true,
                    chatMessages = it.chatMessages.appendChat(
                        CoachChatMessage(kind = CoachChatKind.USER, text = question),
                    ),
                )
            }

            val answer: CoachAnswer = try {
                askCoach(question)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Exception) {
                // 兜底：用例内部已把网络/解析失败转成可识别结果，这里只兜住意外异常，绝不冒泡到 UI。
                CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR)
            }

            val bubble: CoachChatMessage = when (answer) {
                is CoachAnswer.Ok -> CoachChatMessage(kind = CoachChatKind.ANSWER, text = answer.text)
                is CoachAnswer.NeedsNetwork -> CoachChatMessage(kind = CoachChatKind.NEEDS_NETWORK)
                is CoachAnswer.Failed -> CoachChatMessage(kind = CoachChatKind.FAILED)
            }
            _uiState.update {
                it.copy(isAsking = false, chatMessages = it.chatMessages.appendChat(bubble))
            }
        }
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

/**
 * 「上一次失败的是哪个动作」——只用于 [AiCoachViewModel.onRetry] 决定重跑谁。
 *
 * 放私有枚举而不是 UiState 字段：它是重试的路由信息，不是界面状态，也不含任何文案。
 */
private enum class FailedAction {
    /** 生成计划失败 → 重试重跑生成。 */
    GENERATE_PLAN,

    /** 建议加载 / 收入失败 → 重试重新加载建议。 */
    LOAD_SUGGESTIONS,

    /** 生成饮食计划失败 → 重试重新生成饮食。 */
    GENERATE_DIET,
}

/** 「问教练」内存里保留的消息条数上限（6 条 = 3 轮问答）。 */
private const val MAX_CHAT_MESSAGES: Int = 6

/**
 * 追加一条消息，并只保留最近 [MAX_CHAT_MESSAGES] 条。
 *
 * 问答**不落库**：页面退出即丢弃，因此这里用纯内存截断，不做任何持久化。
 */
private fun List<CoachChatMessage>.appendChat(message: CoachChatMessage): List<CoachChatMessage> =
    (this + message).takeLast(MAX_CHAT_MESSAGES)
