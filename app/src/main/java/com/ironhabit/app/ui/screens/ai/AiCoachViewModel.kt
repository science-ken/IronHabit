package com.ironhabit.app.ui.screens.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase
import com.ironhabit.app.domain.usecase.CoachInsightResult
import com.ironhabit.app.domain.usecase.CoachInsightUseCase
import com.ironhabit.app.domain.usecase.ExportWeekPackageUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.PlanPreviewHolder
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
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
import kotlinx.datetime.TimeZone

/**
 * 一次「生成饮食计划」的结果（面向 UI 的纯展示数据，子项 B）。
 *
 * ⚠️ **数值全部来自本地纯函数**（[com.ironhabit.app.domain.diet.DietPlanGenerator]）。
 *
 * @property writtenCount 本次实际写入的餐数
 * @property preservedCount 界面上还看得见的用户手改餐数（删掉的那一餐同样不被覆盖，但不计数）
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
 * @property isGenerating 生成计划进行中（本地规则为纯计算，通常很快）
 * @property dietSummary 最近一次「生成饮食」的本地结果（`null` = 本次会话尚未生成过）
 * @property isGeneratingDiet 生成饮食进行中
 * @property insightResult 进度解读：数字永远来自本地聚合
 * @property isLoadingInsight 进度解读加载中
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性提示资源 id
 * @property snackbarArgs 提示的格式化参数（**类型必须与资源占位符一致**：`%1$d` 传 Int、`%1$s` 传 String；
 *   传错类型会在 `stringResource` 格式化时抛 `IllegalFormatConversionException` 直接崩溃）
 */
data class AiCoachUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile = UserProfile(),
    val isGenerating: Boolean = false,
    /** 预览已备好 → 界面跳一次「本周计划预览」页，跳完立即消费掉。 */
    val previewRequested: Boolean = false,
    /** 最近一次「生成饮食」的**本地**结果（`null` = 本次会话尚未生成过）。 */
    val dietSummary: DietSummaryUi? = null,
    /** 生成饮食进行中。 */
    val isGeneratingDiet: Boolean = false,
    /** 进度解读。`null` = 还没算过；数字来自本地聚合。 */
    val insightResult: CoachInsightResult? = null,
    /** 进度解读进行中（只算本地聚合，通常瞬间完成）。 */
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
)

/**
 * 「AI 教练」ViewModel（**全程本地，App 不联网**）。
 *
 * 职责：
 * - 暴露档案流与最新体重（用于「教练解读」的统计口径 / 建议摄入）；
 * - 调 [GenerateTrainingPlanUseCase] 生成计划（**手改行由 UseCase 保证不被覆盖**）；
 * - 调 [GenerateDietPlanUseCase] 生成饮食（数值全部本地算）；
 * - 调 [CoachInsightUseCase] 做进度解读、[BuildWeeklyReviewUseCase] 做周复盘。
 *
 * ⚠️ **诚实原则（每条都必须成立）**：
 * 1. 只有**用户从外部 AI 粘回来并确认采纳**的那份，才标「来自你问的外部 AI」；内置生成不贴来源徽章；
 * 2. 任何数值（热量 / 蛋白质 / 打卡统计）都由本地纯函数算 —— 删掉远端之前成立，之后同样成立；
 * 3. 页面上不许出现「AI 正在回答 / 联网中」这类已经不可能发生的状态。
 */
@HiltViewModel
class AiCoachViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val generateTrainingPlan: GenerateTrainingPlanUseCase,
    private val planPreviewHolder: PlanPreviewHolder,
    private val generateDietPlan: GenerateDietPlanUseCase,
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

    init {
        viewModelScope.launch {
            settingsRepository.profile().collect { profile ->
                _uiState.update {
                    it.copy(isLoading = false, profile = profile)
                }
            }
        }
        // 进度解读 + 周复盘都在本地算；补充动作建议已搬去「动作库」分段，
        // 用户真进了那一屏才拉 —— 原来这里发两次，第二次还是没人看的。
        loadInsight()
        loadWeeklyReview()
    }

    /**
     * 进度解读：页面打开时自动跑一次。
     *
     * 纯本地聚合，不发网络。失败也不写 [AiCoachUiState.errorRes] —— 拿不到数字时这一格
     * 只是空着，不该弹一个吓人的错误卡。
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
     * 生成 / 重新生成**今日饮食计划**。
     *
     * **数值一律以本地为准**：跑 [GenerateDietPlanUseCase] 写入今日餐次，
     * 用户手改过的那一餐不被覆盖（红线）。
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
                            snackbarRes = hintRes,
                            snackbarArgs = hintArgs,
                        )
                    }
                }
                .onFailure {
                    lastFailedAction = FailedAction.GENERATE_DIET
                    _uiState.update {
                        it.copy(isGeneratingDiet = false, errorRes = R.string.error_save_failed)
                    }
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

/** 一周的天数（周偏移换算用）。 */
private const val DAYS_PER_WEEK: Int = 7
