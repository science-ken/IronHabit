package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ExternalPlanNote
import com.ironhabit.app.domain.ai.external.ImportedMealDraft
import com.ironhabit.app.domain.ai.external.MealSlotSnapshot
import com.ironhabit.app.domain.ai.external.ProfileFieldDiff
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本次会话里的**计划预览快照**。
 *
 * 「让 AI 生成」先算不写（[GenerateTrainingPlanUseCase.preview]），结果要跨一次路由跳转
 * 交给预览页 —— 草案是几十条带外键的对象，塞进导航参数既不合适也不安全，所以放这里。
 *
 * 故意**不落盘**：进程被杀，预览就没了，用户回来看到的是库里的真实状态（没写就是没写）。
 * 这与「撤销这次导入只在本次会话有效」是同一条口径。
 */
@Singleton
class PlanPreviewHolder @Inject constructor() {

    private var current: PlanPreview? = null

    /**
     * 外部文档导入时的"哪些条目没进来、为什么"清单。
     *
     * 跟着草案走同一份生命周期（内存态、清掉就没了），因为它说的就是**这一份**草案：
     * 分开存会出现"看着上一次的清单采纳这一次的计划"那种错配。
     */
    private var importNotes: List<ExternalPlanNote> = emptyList()

    /**
     * 这份文档还想改的档案项（已与当前档案比过，只留下真的会变的）。
     *
     * 和 [importNotes] 同一份生命周期：它说的就是**这一份**导入，分开存会错配。
     */
    private var profileDiffs: List<ProfileFieldDiff> = emptyList()

    /** 「天 × 动作」→ 模型写的那句"为什么"。同样不落库，只在预览页折叠显示。 */
    private var reasons: Map<Pair<Int, Long>, String> = emptyMap()

    /**
     * 这份文档想排的餐次（外部导入路才有；内置生成恒为空）。
     *
     * 和草案同一份生命周期：分开存就会出现"看着上一份文档的吃、这一份文档的练"。
     */
    private var dietDrafts: List<ImportedMealDraft> = emptyList()

    /** 目标周**现在**的餐次状态：预览页那两句「保留不动 / 改了它历史会变」的判据。 */
    private var mealSlots: List<MealSlotSnapshot> = emptyList()

    fun set(
        preview: PlanPreview,
        importNotes: List<ExternalPlanNote> = emptyList(),
        profileDiffs: List<ProfileFieldDiff> = emptyList(),
        reasons: Map<Pair<Int, Long>, String> = emptyMap(),
        dietDrafts: List<ImportedMealDraft> = emptyList(),
        mealSlots: List<MealSlotSnapshot> = emptyList(),
    ) {
        current = preview
        this.importNotes = importNotes
        this.profileDiffs = profileDiffs
        this.reasons = reasons
        this.dietDrafts = dietDrafts
        this.mealSlots = mealSlots
    }

    /** 取出当前快照但不消耗它（页面重建、配置变更时还要能再渲染一次）。 */
    fun peek(): PlanPreview? = current

    /** 导入清单（内置生成路恒为空 → 预览页那一块整块不显示）。 */
    fun peekImportNotes(): List<ExternalPlanNote> = importNotes

    fun peekProfileDiffs(): List<ProfileFieldDiff> = profileDiffs

    fun peekReasons(): Map<Pair<Int, Long>, String> = reasons

    fun peekDietDrafts(): List<ImportedMealDraft> = dietDrafts

    fun peekMealSlots(): List<MealSlotSnapshot> = mealSlots

    /** 采纳完 / 取消完必须清掉，避免下一次进来看到上一次的陈旧草案。 */
    fun clear() {
        current = null
        importNotes = emptyList()
        profileDiffs = emptyList()
        reasons = emptyMap()
        dietDrafts = emptyList()
        mealSlots = emptyList()
    }
}
