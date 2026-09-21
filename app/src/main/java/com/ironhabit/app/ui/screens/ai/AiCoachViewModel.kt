package com.ironhabit.app.ui.screens.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.AskCoachUseCase
import com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase
import com.ironhabit.app.domain.usecase.CoachAnswer
import com.ironhabit.app.domain.usecase.CoachInsightResult
import com.ironhabit.app.domain.usecase.CoachInsightUseCase
import com.ironhabit.app.domain.usecase.ExplainDietUseCase
import com.ironhabit.app.domain.usecase.ExportWeekPackageUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.PlanPreviewHolder
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
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
 * @property isGenerating 生成计划进行中（本地规则为纯计算，通常很快）
 * @property chatMessages 「问教练」最近若干轮消息（**只在内存里，问答不落库**）
 * @property chatInput 「问教练」输入框当前内容
 * @property isAsking 正在等 AI 回答（发送中：输入框与按钮都禁用）
 * @property dietSummary 最近一次「生成饮食」的本地结果（`null` = 本次会话尚未生成过）
 * @property isGeneratingDiet 生成饮食进行中
 * @property dietAnalysis 远端 AI 的「为什么这样吃」分析；`null` = 未联网 / 失败（此时 UI 显示本地依据卡）
 * @property insightResult 进度解读（子项 C）：数字永远来自本地聚合，联网成功时多一段 AI 文案
 * @property isLoadingInsight 进度解读加载中（离线时为本地计算，很快）
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性提示资源 id
 * @property snackbarArgs 提示的格式化参数（**类型必须与资源占位符一致**：`%1$d` 传 Int、`%1$s` 传 String；
 *   传错类型会在 `stringResource` 格式化时抛 `IllegalFormatConversionException` 直接崩溃）
 */
data class AiCoachUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile = UserProfile(),
    val currentWeightKg: Float? = null,
    val isGenerating: Boolean = false,
    /** 预览已备好 → 界面跳一次「本周计划预览」页，跳完立即消费掉。 */
    val previewRequested: Boolean = false,
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
    /** 进度解读（子项 C）。`null` = 还没算过；数字来自本地聚合，AI 文案可选。 */
    val insightResult: CoachInsightResult? = null,
    /** 进度解读进行中（离线只算本地聚合，通常瞬间完成）。 */
    val isLoadingInsight: Boolean = false,

    /**
     * 周复盘（P2）：本周 / 上一周的实际训练数据（**全部本地算出来，不联网、不花 token**）。
     *
     * `null` = 还没算出来（首帧）。
     */
    val weeklyReview: WeeklyReview? = null,
    /** 周复盘整理中。 */
    val isLoadingReview: Boolean = false,
    /** 周偏移：`0` = 本周，`-1` = 上一周（往期只读回看）。 */
    val weekOffset: Int = 0,
    /**
     * 已生成的数据包 JSON（非 `null` = 「AI 会看到什么」弹层正在显示）。
     *
     * 只在用户点「导出数据包」时才算，不让首帧多跑一次序列化。
     */
    val weekPackageJson: String? = null,
    /** 数据包生成中。 */
    val isBuildingPackage: Boolean = false,
    /** 数据包粒度：`true` = 明细 + 汇总（默认，定稿 2B）；`false` = 只传汇总（省 token）。 */
    val includePackageDetails: Boolean = true,

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
 * 「AI 教练」ViewModel（联网可选 · 本地规则兜底）。
 *
 * 职责：
 * - 暴露档案流与最新体重（用于「教练解读」的统计口径 / 建议摄入）；
 * - 调 [GenerateTrainingPlanUseCase] 生成计划（**手改行由 UseCase 保证不被覆盖**）；
 * - 调 [GenerateDietPlanUseCase] 生成饮食（数值全部本地算，见 [ExplainDietUseCase] 只补文字）；
 * - 调 [AskCoachUseCase] 做自由问答、[CoachInsightUseCase] 做进度解读。
 *
 * ⚠️ **诚实原则（每条都必须成立）**：
 * 1. 只有**真的调用了 DeepSeek** 才允许显示「AI 分析 / AI 生成」字样（来源由 UseCase 如实回传）；
 * 2. 未联网 / 未配 Key / 调用失败时一律回落本地规则，并**明确标注**是本地规则；
 * 3. 任何数值（热量 / 蛋白质 / 打卡统计）都由本地纯函数计算，远端只提供文字；
 * 4. 自由问答**不落库**，只在内存里保留最近若干轮。
 */
@HiltViewModel
class AiCoachViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    bodyMetricRepository: BodyMetricRepository,
    private val aiCredentialsStore: AiCredentialsStore,
    private val generateTrainingPlan: GenerateTrainingPlanUseCase,
    private val planPreviewHolder: PlanPreviewHolder,
    private val askCoach: AskCoachUseCase,
    private val generateDietPlan: GenerateDietPlanUseCase,
    private val explainDiet: ExplainDietUseCase,
    private val coachInsight: CoachInsightUseCase,
    private val buildWeeklyReview: BuildWeeklyReviewUseCase,
    private val exportWeekPackage: ExportWeekPackageUseCase,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiCoachUiState())
    val uiState: StateFlow<AiCoachUiState> = _uiState.asStateFlow()

    /**
     * 最近一次失败的动作（供 [onRetry] 决定重跑哪一个）。
     *
     * 私有字段：只影响「重试跑什么」，不进 [AiCoachUiState]，界面文案一律走资源 id。
     */
    private var lastFailedAction: FailedAction = FailedAction.GENERATE_PLAN

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
        // 开屏只自动要这一次远程（解读）；补充动作建议已经搬去「动作库」分段，
        // 用户真进了那一屏才拉 —— 原来这里发两次，第二次还是没人看的。
        loadInsight()
        loadWeeklyReview()
    }

    /**
     * 进度解读（子项 C）。
     *
     * 页面打开时自动跑这一次（开屏唯一自动发出的远程）：离线只做**本地聚合**（瞬间完成，不发网络），
     * 联网且已配 Key 时额外取一段 AI 文案（[CoachInsightResult.source] 会如实标注来源）。
     * 失败也会返回带本地数字的结果，因此这里**不会**写 [AiCoachUiState.errorRes]。
     *
     * @param windowDays 统计窗口，默认 [CoachInsightUseCase.WINDOW_DAYS]（近 14 天）
     */
    fun loadInsight(windowDays: Int = CoachInsightUseCase.WINDOW_DAYS) {
        if (_uiState.value.isLoadingInsight) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingInsight = true) }
            val result: CoachInsightResult = try {
                coachInsight(windowDays)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Exception) {
                // 兜底：用例内部已把失败转成本地结果，这里只兜住意外异常。
                CoachInsightResult()
            }
            _uiState.update { it.copy(isLoadingInsight = false, insightResult = result) }
        }
    }

    // ---------------- 周复盘 + 数据包（P2） ----------------

    /**
     * 载入某一周的复盘（[weekOffset]：`0` = 本周，`-1` = 上一周……）。
     *
     * 纯本地聚合：不联网、不花 token。**失败不写 [AiCoachUiState.errorRes]** ——
     * "算不出来"在这里等价于"这一周没有数据"，界面用语是「本周还没有打卡记录」，
     * 而不是弹一个吓人的错误卡。
     *
     * 周偏移 → 周一仍然走 [BuildWeeklyReviewUseCase.weekStartOf]（**同一套取整规则**，
     * 不允许界面层再写一遍，否则会出现"点了上一周但数字没变"）。
     */
    fun loadWeeklyReview(weekOffset: Int = _uiState.value.weekOffset) {
        // 未来的周没有意义（周复盘是"已经发生的事"）→ 夹到 `≤ 0`，避免任何调用方翻到未来。
        val offset: Int = weekOffset.coerceAtMost(0)
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingReview = true, weekOffset = offset) }
            val weekStart: Long = BuildWeeklyReviewUseCase.weekStartOf(
                todayEpochDay() + offset.toLong() * DAYS_PER_WEEK,
            )
            val review: WeeklyReview? = try {
                buildWeeklyReview(weekStart)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Exception) {
                null
            }
            _uiState.update { it.copy(isLoadingReview = false, weeklyReview = review) }
        }
    }

    /**
     * 生成数据包并打开「AI 会看到什么」弹层（按当前粒度 [AiCoachUiState.includePackageDetails]）。
     *
     * ⚠️ 只序列化，**不落库、不外发**：数据包是一段文本，复制/粘贴由用户自己决定。
     */
    fun onExportPackage() {
        val review: WeeklyReview = _uiState.value.weeklyReview ?: return
        if (_uiState.value.isBuildingPackage) return

        viewModelScope.launch {
            _uiState.update { it.copy(isBuildingPackage = true) }
            val json: String? = try {
                exportWeekPackage(review, _uiState.value.includePackageDetails)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Exception) {
                null
            }
            _uiState.update { state ->
                state.copy(
                    isBuildingPackage = false,
                    weekPackageJson = json,
                    // 序列化失败是"真错误"（不是"没数据"）→ 必须可见。
                    errorRes = if (json == null) R.string.error_save_failed else state.errorRes,
                )
            }
        }
    }

    /** 切换数据包粒度；弹层已经打开时**立即按新粒度重算**（否则开关变了内容没变，看着像坏了）。 */
    fun onTogglePackageDetails() {
        val next: Boolean = !_uiState.value.includePackageDetails
        _uiState.update { it.copy(includePackageDetails = next) }
        if (_uiState.value.weekPackageJson != null) onExportPackage()
    }

    /** 关闭数据包弹层（内容一并清掉，避免下次打开看到旧数据）。 */
    fun onDismissPackage() {
        _uiState.update { it.copy(weekPackageJson = null) }
    }

    /** 已复制到剪贴板（剪贴板由 UI 层写入，VM 只负责给反馈）。 */
    fun onPackageCopied() {
        _uiState.update { it.copy(snackbarRes = R.string.ai_package_copied) }
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

    /** 预览页已经跳过去了，收掉信号；否则每次回到这一页都会再跳一次。 */
    fun onPreviewConsumed() {
        _uiState.update { it.copy(previewRequested = false) }
    }

    /**
     * 生成 / 重新生成训练计划 —— **只算不写**。
     *
     * 以前这里直接 `generateTrainingPlan()` 把整周写进库，AI 教练页上没有任何
     * "这天要不要"的余地；现在算完交给「本周计划预览」页，用户逐天点「采纳这天」才落库。
     */
    fun generatePlan() {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, errorRes = null) }
            runCatching { generateTrainingPlan.preview() }
                .onSuccess { preview ->
                    if (preview.allDrafts.isEmpty()) {
                        // 一条都排不出来（全被手改行 / 模板整日保护挡住）→ 不去预览页空跑。
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                snackbarRes = R.string.msg_plan_nothing_adoptable,
                                snackbarArgs = emptyList(),
                            )
                        }
                        return@onSuccess
                    }
                    planPreviewHolder.set(preview)
                    _uiState.update {
                        it.copy(isGenerating = false, previewRequested = true)
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

    /**
     * 重试上一次失败的动作。
     *
     * 失败不再静默（页面上有内联错误卡），用户点「重试」时回到这里：
     * 先清掉 [AiCoachUiState.errorRes]，再**重跑那个失败的动作** ——
     * 生成计划失败就重跑 [generatePlan]，饮食失败重跑 [generateDiet]。
     */
    fun onRetry() {
        val failed: FailedAction = lastFailedAction
        _uiState.update { it.copy(errorRes = null) }
        when (failed) {
            FailedAction.GENERATE_PLAN -> generatePlan()
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

}

/**
 * 「上一次失败的是哪个动作」——只用于 [AiCoachViewModel.onRetry] 决定重跑谁。
 *
 * 放私有枚举而不是 UiState 字段：它是重试的路由信息，不是界面状态，也不含任何文案。
 */
private enum class FailedAction {
    /** 生成计划失败 → 重试重跑生成。 */
    GENERATE_PLAN,

    /** 生成饮食计划失败 → 重试重新生成饮食。 */
    GENERATE_DIET,
}

/** 「问教练」内存里保留的消息条数上限（6 条 = 3 轮问答）。 */
private const val MAX_CHAT_MESSAGES: Int = 6

/** 一周的天数（周偏移换算用）。 */
private const val DAYS_PER_WEEK: Int = 7

/**
 * 追加一条消息，并只保留最近 [MAX_CHAT_MESSAGES] 条。
 *
 * 问答**不落库**：页面退出即丢弃，因此这里用纯内存截断，不做任何持久化。
 */
private fun List<CoachChatMessage>.appendChat(message: CoachChatMessage): List<CoachChatMessage> =
    (this + message).takeLast(MAX_CHAT_MESSAGES)
