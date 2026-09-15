package com.ironhabit.app.domain.usecase

import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.ai.PlanAdvisor
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.PlanNote
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 「生成训练计划 / 重新生成」的结果摘要（纯数据，供 UI 展示"写了几条 / 保留了几条 / 为什么这样排"）。
 *
 * @property writtenCount 本次实际写入的计划条目数
 * @property preservedCount 被完整保留的**用户手改行**条数（含软删除行），对应 `ai_plan_preserved_hint`
 * @property notes "为什么这样排"的确定性理由，对应 `ai_*` 文案
 * @property source 本次实际使用的来源（本地规则 / AI 联网生成；联网失败回落时为 LOCAL_RULES）
 * @property fallbackReason 走本地规则时的回落原因；`null` = 未发生回落（联网一期 §6.2 N4）
 */
data class GeneratedPlanSummary(
    val writtenCount: Int = 0,
    val preservedCount: Int = 0,
    val notes: List<PlanNote> = emptyList(),
    val source: AdviceSource = AdviceSource.LOCAL_RULES,
    val fallbackReason: RemoteFallbackReason? = null,
    /** 本次实际写入的计划条目（**含 星期/组数/次数/重量**），供 UI 以卡片形式摊开看。 */
    val plans: List<WeekPlan> = emptyList(),
)

/**
 * **生成一周训练计划**（对应预览 `doPlan()`，`docs/ai-coach-local.md` §4.5）。
 *
 * 职责：组装输入 → 调 [PlanAdvisor.planWeek] → **只对可写槽位显式 upsert**。
 *
 * ## 🔒 不变量（每条都有单测 / 代码约束）
 * 1. **手改行完整保留**：`existing` 里 `isUserEdited == true` 的行（**含软删除行**）
 *    一行都不碰 —— 既不在它上面覆盖，也不"复活"它。
 *    保险做法：规则层已排除这些槽位，写入前**再过滤一次**（双保险，见 [invoke]）。
 * 2. **禁用 REPLACE / 禁用"先删再建"**：本用例**没有任何 DELETE 调用**，
 *    写入统一走 `PlanRepository.upsertGenerated`（内部是显式 upsert）。
 * 3. **纯函数外置**：规则判断全在 [PlanAdvisor]，本用例只负责取数与写入。
 *
 * ## 联网一期
 * 顾问调用包 `withContext(ioDispatcher)`：本地实现是纯计算（快），远端实现是
 * **阻塞 HTTP**（[com.ironhabit.app.domain.ai.remote.DeepSeekClient]，30s 超时），
 * 必须离开主线程；是否走远端 / 失败回落由 [com.ironhabit.app.domain.ai.DelegatingPlanAdvisor] 决定。
 *
 * @param advisor 注入接口，不 new 具体实现（本地 / 远端 / 委托切换对本用例透明）
 */
class GenerateTrainingPlanUseCase @Inject constructor(
    private val planRepository: PlanRepository,
    private val exerciseRepository: ExerciseRepository,
    private val checkInRepository: CheckInRepository,
    private val settingsRepository: SettingsRepository,
    private val advisor: PlanAdvisor,
    private val clock: Clock,
    private val timeZone: TimeZone,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend operator fun invoke(): GeneratedPlanSummary {
        val profile = settingsRepository.profile().first()
        val library = exerciseRepository.observeActive().first()
        // 🔒 必须拿**全量**（含软删除行），否则会误写手改/软删槽位把它"复活"。
        val existing = planRepository.observeAllIncludingInactive().first()
        val history = checkInRepository.latestProgressPerExercise().first()
        val today: LocalDate = clock.now().toLocalDateTime(timeZone).date

        // 本地实现 = 纯计算；远端实现 = 阻塞 HTTP（DeepSeekClient 30s 超时）→ 必须在 IO 上跑。
        val proposal = withContext(ioDispatcher) {
            advisor.planWeek(
                profile = profile,
                library = library,
                existing = existing,
                history = history,
                today = today,
            )
        }

        // 🔒 双保险：即使规则层漏判，写入前也再排除一次手改槽位（"天 × 动作"）。
        val blockedSlots: Set<Pair<Int, Long>> = existing
            .filter { it.isUserEdited }
            .map { plan -> plan.dayOfWeek to plan.exerciseId }
            .toSet()

        // ② 只把"可写槽位"收集成待写列表，**整批**交给仓库（内部仍逐条显式 upsert）。
        val drafts: List<WeekPlan> = proposal.days.flatMap { day ->
            day.items.mapIndexedNotNull { index, item ->
                if (day.dayOfWeek to item.exerciseId in blockedSlots) {
                    null // 手改槽位：跳过，不覆盖也不复活
                } else {
                    WeekPlan(
                        exerciseId = item.exerciseId,
                        dayOfWeek = day.dayOfWeek,
                        targetSets = item.targetSets,
                        targetReps = item.targetReps,
                        targetWeightKg = item.targetWeightKg,
                        sortOrder = index,
                    )
                }
            }
        }
        planRepository.upsertGenerated(drafts)

        return GeneratedPlanSummary(
            writtenCount = drafts.size,
            preservedCount = proposal.preservedUserEditedIds.size,
            notes = proposal.notes,
            source = proposal.source,
            fallbackReason = advisor.lastFallbackReason,
            plans = drafts,
        )
    }
}
