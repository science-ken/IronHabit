package com.ironhabit.app.domain.usecase

import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.ai.external.ExternalDocOutcome
import com.ironhabit.app.domain.ai.external.ExternalDocRefusal
import com.ironhabit.app.domain.ai.external.ExternalPlanDocumentParser
import com.ironhabit.app.domain.ai.external.ExternalPlanNote
import com.ironhabit.app.domain.ai.external.ImportedMealDraft
import com.ironhabit.app.domain.ai.external.ImportedNewExercise
import com.ironhabit.app.domain.ai.external.ImportedNewFood
import com.ironhabit.app.domain.ai.external.ProfileFieldDiff
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.FoodRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 把用户从外部 AI 粘回来的文档读成**一份可逐天采纳的预览**（不写库）。
 *
 * 职责边界：
 * - 解析（名字→动作、防线、丢弃清单）在 [ExternalPlanDocumentParser]；
 * - 保护规则（手改槽位不碰、模板归属日不写、落到哪一周）在 [PlanDraftProjector] ——
 *   与内置生成**同一个实现**，不在这里再写一份；
 * - 这里只做三件事：取该周现有的行来喂投影器、把结果归成可分派的三态、以及**关掉陈旧行回收**。
 *
 * ## 🔒 两条只属于"外部来源"的口径
 * 1. [PlanDraftProjector.project] 传 `retireStaleRows = false`：那份文档多半只写了几天，
 *    而且外部 AI 看不见用户本周已有什么 —— "没列出来"不构成意见，据此停用旧行就是毁数据。
 * 2. `preservedUserEditedIds` 在这里补，不让解析器管：解析器只认动作名，够不到行 id；
 *    「已保留 N 条」这个数字必须来自**投影时**看到的真实行，否则预览页会报一个用户找不着的数。
 *
 * ⚠️ 目标周由调用方给（本周 / 下周），这里**不接受** `WeekPlan.TEMPLATE_WEEK_START`（`0`）：
 * 写进 0 就是改「每周相同」那份循环模板，而导入的语义是"排某一周的课"。
 */
class ImportExternalPlanUseCase @Inject constructor(
    private val planRepository: PlanRepository,
    private val exerciseRepository: ExerciseRepository,
    private val foodRepository: FoodRepository,
    private val settingsRepository: SettingsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * @param text 用户粘进来的原文（可带围栏与寒暄）
     * @param weekStartEpochDay 要写进哪一周的周一 epochDay
     */
    suspend operator fun invoke(text: String, weekStartEpochDay: Long): ExternalPlanImport =
        withContext(ioDispatcher) {
            // 动作库取**全量含停用**：停用行不能算"库里没有"，否则建库撞 UNIQUE 再绕回来（死循环）。
            val library = exerciseRepository.getAll()
            // 食物库取**全量含停用**并在解析器里分流：停用行不能算"库里没有"，
            // 否则"建库 → 撞 UNIQUE → 跳过 → 重解析还是'库里没有'"这个死循环就成立了。
            val foods = foodRepository.observeAll().first()
            val profile = settingsRepository.profile().first()
            val parsed = ExternalPlanDocumentParser.parse(
                text = text,
                library = library,
                foods = foods,
                dietaryAvoid = profile.dietaryAvoid,
            )

            when (parsed) {
                is ExternalDocOutcome.Refused -> ExternalPlanImport.Refused(
                    reason = parsed.reason,
                    notes = parsed.notes,
                    analysis = parsed.analysis,
                    // 一份"全是新动作"的文档要能救回来：先让用户确认建库，再重解析一次。
                    newExercises = parsed.newExercises,
                    newFoods = parsed.newFoods,
                )

                is ExternalDocOutcome.Parsed -> {
                    // 🔒 必须拿**该周全量**（含软删除行）：投影器靠它挡手改/软删槽位。
                    val weekRows = planRepository.getRowsForWeek(weekStartEpochDay)
                    val templateEditedRows = planRepository.getRepeatRows()
                        .filter { plan -> plan.isUserEdited }

                    val proposal = parsed.draft.proposal.copy(
                        preservedUserEditedIds = (weekRows + templateEditedRows)
                            .filter { plan -> plan.isUserEdited }
                            .map { plan -> plan.id },
                    )
                    val preview = PlanDraftProjector.project(
                        proposal = proposal,
                        targetWeek = weekStartEpochDay,
                        weekRows = weekRows,
                        templateEditedRows = templateEditedRows,
                        retireStaleRows = false,
                    )

                    if (preview.allDrafts.isEmpty()) {
                        // 文档合法、但能写的训练槽位一个都不剩（全被手改行或模板归属日挡住）。
                        // 这时候跳预览页只会看到一句"没有待采纳的草案"，等于把人支走又不说原因；
                        // 所以连着预览一起回：`preservedCount` / `templateOwnedDays` 就是"为什么没地方写"。
                        //
                        // ⚠️ `allDrafts` 只看训练侧，所以一份"只有饮食"的文档目前会落到这里、
                        // 显示那句"槽位都被你自己的改动挡住了"—— 那句话对它**是错的**。
                        // 刀 3 给预览页加饮食节时一并修（那之后有餐次就该是 Ready）。
                        ExternalPlanImport.NothingAdoptable(
                            preview = preview,
                            notes = parsed.draft.notes,
                            meals = parsed.draft.meals,
                            newFoods = parsed.draft.newFoods,
                        )
                    } else {
                        // 档案差异在这里算，是因为**只有此刻**才知道当前档案是什么：
                        // 值没变的项不进列表，界面就不会出现「目标：增肌 → 增肌」那种噪音勾选。
                        ExternalPlanImport.Ready(
                            preview = preview,
                            notes = parsed.draft.notes,
                            profileDiffs = parsed.draft.profile.diffsAgainst(profile),
                            // 投影成 WeekPlan 时"为什么"会被丢掉（库里没这一列），
                            // 所以在丢之前先按「天 × 动作」摘出来，交给预览页折叠显示。
                            reasons = parsed.draft.proposal.days
                                .flatMap { day ->
                                    day.items.mapNotNull { item ->
                                        item.explanation?.let { explanation ->
                                            (day.dayOfWeek to item.exerciseId) to explanation
                                        }
                                    }
                                }
                                .toMap(),
                            newExercises = parsed.draft.newExercises,
                            meals = parsed.draft.meals,
                            newFoods = parsed.draft.newFoods,
                        )
                    }
                }
            }
        }
}

/** 导入的三态。界面对每一态都有**各自**的说法，不许合并成"成功/失败"两态。 */
sealed interface ExternalPlanImport {

    /**
     * 整份不收（格式不对 / 不是本 App 的合同 / 太大 / 一条有效都没有）。
     *
     * [analysis] 是模型自己写的那段话：它经常在里面直接说了为什么没排（"没收到动作库"），
     * 那是用户唯一能看懂的原因，不显示出来就等于把线索扔掉。
     */
    data class Refused(
        val reason: ExternalDocRefusal,
        val notes: List<ExternalPlanNote> = emptyList(),
        val analysis: String? = null,
        /** 全是新动作的文档不是废文档：先让用户确认建库，再重解析一次就救得回来。 */
        val newExercises: List<ImportedNewExercise> = emptyList(),
        /** 同上，食物侧。 */
        val newFoods: List<ImportedNewFood> = emptyList(),
    ) : ExternalPlanImport

    /**
     * 读通了、也合法，但本周没有一个训练槽位能写（全被保护规则挡住）。
     *
     * [preview] 一定没有任何草案，但它的 `preservedCount` / `templateOwnedDays` 正是界面要说的原因。
     */
    data class NothingAdoptable(
        val preview: PlanPreview,
        val notes: List<ExternalPlanNote>,
        /** 解析出来的餐次：训练侧没地方写，不代表饮食侧也没地方写（刀 3 之前界面还看不到它们）。 */
        val meals: List<ImportedMealDraft> = emptyList(),
        val newFoods: List<ImportedNewFood> = emptyList(),
    ) : ExternalPlanImport

    /**
     * 有可采纳的草案：交给预览页逐天采纳，[notes] 同屏如实列出。
     *
     * [profileDiffs] 是这份文档还想改的档案项（已与当前档案比过、只留下真的会变的）；
     * 空表 = 它没提档案，或者提了但值全和现在一样。
     */
    data class Ready(
        val preview: PlanPreview,
        val notes: List<ExternalPlanNote>,
        val profileDiffs: List<ProfileFieldDiff> = emptyList(),
        /** 「天 × 动作」→ 模型写的那句为什么。只在预览页折叠显示，**不落库**。 */
        val reasons: Map<Pair<Int, Long>, String> = emptyMap(),
        /** 库里没有、文档声明过的动作：等用户在弹层里勾选确认才建进库。 */
        val newExercises: List<ImportedNewExercise> = emptyList(),
        /** 这一周的餐次草案（数字全本地算）。刀 3 起在预览页与训练同屏逐天采纳。 */
        val meals: List<ImportedMealDraft> = emptyList(),
        /** 库里没有的食物：等用户在弹层里确认才建进食物库。 */
        val newFoods: List<ImportedNewFood> = emptyList(),
    ) : ExternalPlanImport
}
