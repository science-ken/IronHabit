package com.ironhabit.app.domain.usecase

import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.ai.external.ExternalDocOutcome
import com.ironhabit.app.domain.ai.external.ExternalDocRefusal
import com.ironhabit.app.domain.ai.external.ExternalPlanDocumentParser
import com.ironhabit.app.domain.ai.external.ExternalPlanNote
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * @param text 用户粘进来的原文（可带围栏与寒暄）
     * @param weekStartEpochDay 要写进哪一周的周一 epochDay
     */
    suspend operator fun invoke(text: String, weekStartEpochDay: Long): ExternalPlanImport =
        withContext(ioDispatcher) {
            val library = exerciseRepository.observeActive().first()
            val parsed = ExternalPlanDocumentParser.parse(text, library)

            when (parsed) {
                is ExternalDocOutcome.Refused ->
                    ExternalPlanImport.Refused(parsed.reason, parsed.notes)

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
                        // 文档合法、但能写的槽位一个都不剩（全被手改行或模板归属日挡住）。
                        // 这时候跳预览页只会看到一句"没有待采纳的草案"，等于把人支走又不说原因；
                        // 所以连着预览一起回：`preservedCount` / `templateOwnedDays` 就是"为什么没地方写"。
                        ExternalPlanImport.NothingAdoptable(preview, parsed.draft.notes)
                    } else {
                        ExternalPlanImport.Ready(preview, parsed.draft.notes)
                    }
                }
            }
        }
}

/** 导入的三态。界面对每一态都有**各自**的说法，不许合并成"成功/失败"两态。 */
sealed interface ExternalPlanImport {

    /** 整份不收（格式不对 / 不是本 App 的合同 / 太大 / 一条有效都没有），[notes] 在"全被挡掉"时仍有内容。 */
    data class Refused(val reason: ExternalDocRefusal, val notes: List<ExternalPlanNote> = emptyList()) : ExternalPlanImport

    /**
     * 读通了、也合法，但本周没有一个槽位能写（全被保护规则挡住）。
     *
     * [preview] 一定没有任何草案，但它的 `preservedCount` / `templateOwnedDays` 正是界面要说的原因。
     */
    data class NothingAdoptable(
        val preview: PlanPreview,
        val notes: List<ExternalPlanNote>,
    ) : ExternalPlanImport

    /** 有可采纳的草案：交给预览页逐天采纳，[notes] 同屏如实列出。 */
    data class Ready(val preview: PlanPreview, val notes: List<ExternalPlanNote>) : ExternalPlanImport
}
