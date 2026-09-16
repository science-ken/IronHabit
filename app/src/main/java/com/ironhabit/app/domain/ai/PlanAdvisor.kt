package com.ironhabit.app.domain.ai

import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseProgress
import com.ironhabit.app.domain.model.ExerciseSuggestion
import com.ironhabit.app.domain.model.PlanProposal
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import kotlinx.datetime.LocalDate

/**
 * 计划 / 建议来源的统一抽象。
 *
 * 本期**只有一个实现** [LocalRuleAdvisor]（`source = [AdviceSource.LOCAL_RULES]`）；
 * 将来接入联网模型时，新增 `RemoteLlmAdvisor`（`source = [AdviceSource.REMOTE_LLM]`）实现同一接口，
 * **UI 与 UseCase 不改**（依赖注入切换实现即可）。
 *
 * ⚠️ 接口刻意保持"**纯函数**"形状：**无 suspend、无 IO、无 Android 依赖、无随机** → 便于 JVM 单测；
 * 联网实现的耗时 / 线程交由 UseCase 层（`withContext(Dispatchers.IO)`）处理。
 */
interface PlanAdvisor {

    /** 建议来源（诚实标注）。 */
    val source: AdviceSource

    /**
     * 最近一次调用的**回落原因**（联网一期 §6.2 N4）。
     *
     * `null` = 未发生回落；非 `null` = 本次结果实际来自本地规则，但原因不是"用户选了本地"。
     * 默认实现恒为 `null`（[LocalRuleAdvisor] 等本地实现天然不回落）；
     * 只有 [com.ironhabit.app.domain.ai.DelegatingPlanAdvisor] 会覆写。
     */
    val lastFallbackReason: RemoteFallbackReason? get() = null

    /**
     * 【纯函数 ①】按档案生成「一周训练计划草案」。
     *
     * 不变量（**必须写死，必须单测**）：
     *  1. **[existing] 中 `isUserEdited == true` 的行（**含软删除行**）→ 完整保留**：
     *     既不生成会覆盖它的条目，也不"复活"它（对应 `schema-v2.md` §6.3 坑 3/4/6）。
     *  2. 只用 [profile].equipment 里**实际拥有**的器械（空集视为 `{NONE}` = 仅自重）；
     *     含 [profile].injuryAreas 会刺激到的动作 → **机械排除**，并用**未受该伤病影响的邻近肌群**
     *     低冲击动作替代（`PlanReason.INJURY_SAFE`）—— 见 P1 的"伤病从排除改替代"。
     *  3. 输出**只包含"可写槽位"**的草案；是否落库由 UseCase 决定（本函数不碰仓库）。
     *  4. 每周训练天数读 [profile] 的 `trainingDaysPerWeek`（P1 起 `3–6` 可选；
     *     默认 3 天 = 旧版钉死的行为，老用户升级前后排出来的计划一致）。
     *  5. 训练量参数（组次区间 / 每日动作数 / 每周有氧数 / 加重步长）由
     *     [ProfileLoadPolicy.of] 从档案推导（P1）—— 规则层不自己硬编码这些数字。
     *
     * @param profile  用户档案（见 `schema-v3-meals.md` §7.5.2 的 `UserProfile`）
     * @param library  可选动作全集（内置 + 自建 + 已收编的推荐动作）
     * @param existing 当前周计划**全部**行（**含 `isActive == false` 的软删除行**）
     * @param history  每个动作"最近一次完成情况"，用于渐进超负荷（缺省为空 = 不做超负荷调整）
     * @param today    今天（决定训练日在周内的排布起点 + 训练重点轮换相位）
     * @param bodyWeightKg 当前体重（来自 `body_metrics` 最新一条）；`null` = 用户没记过体重
     *   → 跳过"体重 vs 目标体重"的规则（**不猜**，不用 0 冒充）
     * @return 计划草案（**纯数据**，不落库、不改状态）
     */
    fun planWeek(
        profile: UserProfile,
        library: List<Exercise>,
        existing: List<WeekPlan>,
        history: List<ExerciseProgress> = emptyList(),
        today: LocalDate,
        bodyWeightKg: Float? = null,
    ): PlanProposal

    /**
     * 【纯函数 ②】按档案筛出「补充动作」建议。
     *
     * 幂等（**必须单测**）：已在 [existing] 中的动作（按 `name` 去重）**不再返回**
     * → 多次调用结果收敛，不会重复推荐、不会重复入库。
     *
     * @param profile    用户档案（伤病 → 替代动作；器械 → 可行性；目标 → 补强方向）
     * @param candidates 候选池（"补强动作"全集，按档案打标）
     * @param existing   用户动作库现有动作（按 `name` 去重）
     * @return 建议列表（**只含未收入的动作**，纯数据）
     */
    fun suggestExercises(
        profile: UserProfile,
        candidates: List<Exercise>,
        existing: List<Exercise>,
    ): List<ExerciseSuggestion>
}
