package com.ironhabit.app.domain.usecase

import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.ai.PlanAdvisor
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.PlanBasisItem
import com.ironhabit.app.domain.model.PlanNote
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.util.DateUtils
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
 * @property preservedCount 被完整保留的**用户手改行**条数（含软删除行）
 * @property retiredCount 本次**被回收的陈旧 AI 行**条数（上版生成、本次不再出现 → 已停用，修复 C2）
 * @property notes "为什么这样排"的确定性理由（`PlanReason` 枚举，文案由界面侧决定要不要摊开）
 * @property source 本次实际使用的来源（本地规则 / AI 联网生成；联网失败回落时为 LOCAL_RULES）
 * @property fallbackReason 走本地规则时的回落原因；`null` = 未发生回落（联网一期 §6.2 N4）
 */
data class GeneratedPlanSummary(
    val writtenCount: Int = 0,
    val preservedCount: Int = 0,
    val retiredCount: Int = 0,
    val notes: List<PlanNote> = emptyList(),
    val source: AdviceSource = AdviceSource.LOCAL_RULES,
    val fallbackReason: RemoteFallbackReason? = null,
    /** 本次实际写入的计划条目（**含 星期/组数/次数/重量**），供 UI 以卡片形式摊开看。 */
    val plans: List<WeekPlan> = emptyList(),
    /** 远端 AI 返回的自由文本分析（仅 REMOTE_LLM 有值；本地规则恒为 null）。 */
    val analysis: String? = null,
    /** 本地规则的「生成依据」要点（仅 LOCAL_RULES 有内容）。 */
    val basis: List<PlanBasisItem> = emptyList(),
)

/**
 * 生成结果的**预览态**：草案已经算完、保护规则已经跑过，但**一条都没写进库**。
 *
 * 界面把它摊开给用户看，用户挑完哪几天要，再交给
 * [GenerateTrainingPlanUseCase.commit] 落库。它只是内存里的一份快照，
 * 跨一次页面跳转够用，杀进程就没了 —— 这正是"撤销只在本次会话有效"的口径。
 *
 * @property weekStartEpochDay 这批草案属于哪一周（周一 epochDay；`0` = 「每周相同」那份，生成路径不会出现）
 * @property draftsByDay `星期(1..7) → 该天可写的草案行`；已经被手改槽位和整日模板保护过滤过
 * @property weekRowsForRetirement 生成时读到的**该周现有行快照**，只用于回收陈旧 AI 行
 * @property templateOwnedDays 由「每周相同」模板（含用户手改）负责、本周整日不写的天，对应界面「已保留」
 */
data class PlanPreview(
    val weekStartEpochDay: Long,
    val draftsByDay: Map<Int, List<WeekPlan>>,
    val weekRowsForRetirement: List<WeekPlan>,
    val templateOwnedDays: Set<Int>,
    val preservedCount: Int = 0,
    val notes: List<PlanNote> = emptyList(),
    val source: AdviceSource = AdviceSource.LOCAL_RULES,
    val fallbackReason: RemoteFallbackReason? = null,
    val analysis: String? = null,
    val basis: List<PlanBasisItem> = emptyList(),
) {
    /** 这次生成排了课的天（有草案可采纳的）。 */
    val writableDays: Set<Int> get() = draftsByDay.keys

    /** 全部草案（按天、按 sortOrder 摊平）。 */
    val allDrafts: List<WeekPlan> get() = draftsByDay.toSortedMap().values.flatten()
}

/**
 * **生成一周训练计划**（对应预览 `doPlan()`，`docs/ai-coach-local.md` §4.5）。
 *
 * 职责：组装输入 → 调 [PlanAdvisor.planWeek] → **只对可写槽位显式 upsert**。
 *
 * ## 🔒 不变量（每条都有单测 / 代码约束）
 * 1. **手改行完整保留**：`isUserEdited == true` 的行（**含软删除行**）
 *    一行都不碰 —— 既不在它上面覆盖，也不"复活"它。
 *    手改行有**两个来源**，都必须保护（P0-3）：
 *    - 目标周的专属行（`getRowsForWeek`）；
 *    - 「每周相同」模板行（`getRepeatRows`）—— 某天在本周没有启用专属行时，
 *      本周生效计划来自模板，模板里的手改同样不能被绕过。
 *    且**模板里被手改过、本周又缺启用专属行的天，本周整日不写**（P0-3）：
 *    一旦为该天写入专属行，`WeekPlanWeekResolver` 的逐天覆盖规则会让它不再回落模板，
 *    用户的手改（含删掉的动作）就被静默绕过（"删掉的深蹲又回来了"）。
 *    陈旧行回收因此**只看 `weekRows`**：模板行并进回收会把整份「每周相同」停用。
 *    保险做法：规则层已排除这些槽位，写入前**再过滤一次**（双保险，见 [invoke]）。
 * 2. **禁用 REPLACE / 禁用"先删再建"**：本用例**没有任何 DELETE 调用**，
 *    写入统一走 `PlanRepository.upsertGenerated`（内部是显式 upsert）。
 * 3. **纯函数外置**：规则判断全在 [PlanAdvisor]，本用例只负责取数与写入。
 * 4. **回收陈旧 AI 行（修复 C2）**：上版生成、本次不再出现的启用行 → 调用
 *    [PlanRepository.deactivateGenerated] 置 `isActive = false`（**仍是显式 `UPDATE`，无 DELETE**）；
 *    **用户手改行（含软删除行）永不回收**。写入顺序：先 upsert 新计划，再回收陈旧行。
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
    private val bodyMetricRepository: BodyMetricRepository,
    private val settingsRepository: SettingsRepository,
    private val advisor: PlanAdvisor,
    private val clock: Clock,
    private val timeZone: TimeZone,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 生成**某一周**的训练计划。
     *
     * ## 🔒 P3：必须指定"哪一周"
     * 计划按周存放（`week_start_epoch_day`）。生成时：
     * - `weekRows` = **该周的行**（含软删行）—— 不能拿全表，否则会把别的周误当成
     *   "这一周的现有内容"；
     * - 写入的草案**带上该周的周一** —— 不带的话会落到 `0`（=「每周相同」那份），
     *   于是"给下周生成"会**偷偷改掉每周循环的那份计划**；
     * - 陈旧行回收也只在该周范围内（`weekRows`）。
     *
     * ## 🔒 P0-3：模板手改行只借来"保护"，不借来"回收"
     * `templateEditedRows` 参与规则层入参 / `blockedSlots` / `userOwnedDays` 三件事，
     * 但**不参与**陈旧行回收 —— 见类注释不变量 1。
     *
     * @param weekStartEpochDay 目标周的周一；`null` = 今天所在的那一周
     */
    suspend fun preview(weekStartEpochDay: Long? = null): PlanPreview {
        val profile = settingsRepository.profile().first()
        val library = exerciseRepository.observeActive().first()
        val today: LocalDate = clock.now().toLocalDateTime(timeZone).date
        val targetWeek: Long = weekStartEpochDay
            ?: DateUtils.weekStartMon1(today.toEpochDays().toLong())
        // 🔒 必须拿**该周全量**（含软删除行），否则会误写手改/软删槽位把它"复活"。
        val weekRows = planRepository.getRowsForWeek(targetWeek)
        // 🔒 P0-3：「每周相同」模板里被用户手改过的行（**含软删行**）——**只做保护**，两个去向：
        //    ① 传给规则层 / `blockedSlots` → 这些槽位不被覆盖、不被"复活"；
        //    ② 推出 `userOwnedDays`（下方）→ 该天本周没有启用专属行时，本周生效计划本来
        //       就来自模板，被手改过的天必须**整日**交回模板。
        //    ⚠️ 它**绝不参与陈旧行回收** —— 模板行不是"本周的行"，并进回收会把整份
        //       「每周相同」计划当成陈旧 AI 行停用。
        val templateEditedRows: List<WeekPlan> =
            planRepository.getRepeatRows().filter { plan -> plan.isUserEdited }
        // 规则层入参 = 该周行 + 模板手改行（规则层据此排除"受保护的槽位"）。
        val existing: List<WeekPlan> = weekRows + templateEditedRows
        val history = checkInRepository.latestProgressPerExercise().first()
        // P1：当前体重（体重唯一真源是 body_metrics，不是档案）→ 供"体重 vs 目标体重"规则使用。
        // 取不到就是 null（用户没记过体重）→ 规则层会**跳过**体重相关判断，而不是拿 0 去算。
        val bodyWeightKg: Float? = bodyMetricRepository.latest(BodyMetricType.WEIGHT)?.value

        // 本地实现 = 纯计算；远端实现 = 阻塞 HTTP（DeepSeekClient 30s 超时）→ 必须在 IO 上跑。
        val proposal = withContext(ioDispatcher) {
            advisor.planWeek(
                profile = profile,
                library = library,
                existing = existing,
                history = history,
                today = today,
                bodyWeightKg = bodyWeightKg,
            )
        }

        // 🔒 双保险：即使规则层漏判，写入前也再排除一次手改槽位（"天 × 动作"）。
        val blockedSlots: Set<Pair<Int, Long>> = existing
            .filter { it.isUserEdited }
            .map { plan -> plan.dayOfWeek to plan.exerciseId }
            .toSet()

        // 🔒 P0-3 日级保护：某天在本周**没有启用专属行**时，本周的生效计划来自「每周相同」那份
        // （`WeekPlanWeekResolver` 的逐天覆盖规则）。这类天若写入任何专属行，从那一刻起该天
        // 就不再回落模板 → 用户在模板里手改/删掉的动作在本周被静默绕过（"删掉的深蹲又回来了"）。
        // 因此：模板里被手改过、且本周没有启用专属行的天，本周**一条都不写**，整体交回模板。
        val weekActiveDays: Set<Int> = weekRows.filter { plan -> plan.isActive }.map { plan -> plan.dayOfWeek }.toSet()
        val userOwnedDays: Set<Int> = templateEditedRows
            .map { plan -> plan.dayOfWeek }
            .filter { day -> day !in weekActiveDays }
            .toSet()

        // ② 只把"可写槽位"收集成待写列表，**整批**交给仓库（内部仍逐条显式 upsert）。
        val drafts: List<WeekPlan> = proposal.days.flatMap { day ->
            if (day.dayOfWeek in userOwnedDays) {
                emptyList() // 该天由模板（含用户手改）负责 → 不写专属行，避免绕过用户改动
            } else {
                day.items.mapIndexedNotNull { index, item ->
                    if (day.dayOfWeek to item.exerciseId in blockedSlots) {
                        null // 手改槽位：跳过，不覆盖也不复活
                    } else {
                        WeekPlan(
                            exerciseId = item.exerciseId,
                            dayOfWeek = day.dayOfWeek,
                            // P3：落到**目标周**（不带这一维就会写进「每周相同」那份）。
                            weekStartEpochDay = targetWeek,
                            targetSets = item.targetSets,
                            targetReps = item.targetReps,
                            targetWeightKg = item.targetWeightKg,
                            // 修复 C3：把有氧时长（分钟）一并写入，避免生成链路丢字段。
                            targetDurationMin = item.targetDurationMin,
                            sortOrder = index,
                        )
                    }
                }
            }
        }
        return PlanPreview(
            weekStartEpochDay = targetWeek,
            draftsByDay = drafts.groupBy { plan -> plan.dayOfWeek },
            // 回收陈旧行要用到它，但**不在 commit 时回读数据库**：预览期间再读一次会让
            // `invoke()` 变成两次 `getRowsForWeek`，而"只读一次"是既有单测钉住的口径。
            weekRowsForRetirement = weekRows,
            templateOwnedDays = userOwnedDays,
            preservedCount = proposal.preservedUserEditedIds.size,
            notes = proposal.notes,
            source = proposal.source,
            fallbackReason = advisor.lastFallbackReason,
            analysis = proposal.analysis,
            basis = proposal.basis,
        )
    }

    /**
     * 把预览里**被采纳的那几天**写进库；未采纳的天一行都不碰。
     *
     * 陈旧行回收同样**只在 [days] 范围内**做 —— 只采纳周三，就不能把周一上版生成的
     * 那几条当成"本次不再出现"给停用掉。
     *
     * B-3：草案为空（远端 AI 幻觉被全部过滤 / 本地无可排内容）→ **绝不回收现有行**。
     * 空草案只说明"本次没有可写入的内容"，绝不是"用户这周什么都不练"；
     * 若照旧走回收，staleRows 会包含全部启用非手改行 → 一次空结果清空用户整周计划。
     */
    suspend fun commit(preview: PlanPreview, days: Set<Int>): GeneratedPlanSummary {
        val drafts: List<WeekPlan> = preview.draftsByDay
            .filterKeys { day -> day in days }
            .toSortedMap()
            .values
            .flatten()

        val writtenCount: Int
        val retiredCount: Int
        if (drafts.isNotEmpty()) {
            planRepository.upsertGenerated(drafts)
            // writtenCount 语义 = 本次写出的草案条数（以本地 drafts 为准，不依赖仓库返回值）。
            writtenCount = drafts.size

            // ③ 修复 C2：回收**上版生成、本次不再出现**的陈旧 AI 行（只停用，不删除）。
            //    过滤：已启用 + 非用户手改 + 本次未写入 + 落在本次采纳的天里；
            //    用户手改行（含软删除行）永不淘汰。
            val writtenSlots: Set<Pair<Int, Long>> =
                drafts.map { plan -> plan.dayOfWeek to plan.exerciseId }.toSet()
            val staleRows: List<WeekPlan> = preview.weekRowsForRetirement.filter { row ->
                row.isActive &&
                    !row.isUserEdited &&
                    row.dayOfWeek in days &&
                    (row.dayOfWeek to row.exerciseId) !in writtenSlots
            }
            retiredCount = planRepository.deactivateGenerated(staleRows)
        } else {
            writtenCount = 0
            retiredCount = 0
        }

        return GeneratedPlanSummary(
            writtenCount = writtenCount,
            preservedCount = preview.preservedCount,
            retiredCount = retiredCount,
            notes = preview.notes,
            source = preview.source,
            fallbackReason = preview.fallbackReason,
            plans = drafts,
            analysis = preview.analysis,
            basis = preview.basis,
        )
    }
}
