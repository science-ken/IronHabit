package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.PlanProposal
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.WeekPlan

/**
 * 把一份 [PlanProposal]（草案）**投影**成 [PlanPreview] —— 决定「哪些槽位能写、写到哪一周」。
 *
 * ## 为什么单独成函数
 * 生成训练计划现在有**两条来源**：app 内置（本地规则 / DeepSeek）与用户从**外部 AI 粘回来的文档**。
 * 来源可以不同，但保护用户既有数据的规则**必须只有一份实现** —— 所以取数与调顾问留在各自的
 * UseCase 里，投影这一步（下面四条不变量）共享这里。写第二份的必然结果是只改其中一份。
 *
 * 本文件**零 IO、零 Android 依赖**，纯函数，可被 JVM 单测直接覆盖。
 *
 * ## 🔒 四条不变量（逐条对应 `GenerateTrainingPlanUseCase` 的类注释）
 * 1. **手改行完整保留**：`isUserEdited == true` 的行（**含软删除行**）所在槽位一条都不写 ——
 *    既不覆盖，也不"复活"。手改行有两个来源，都必须保护：目标周的专属行（`weekRows`）
 *    与「每周相同」模板行（`templateEditedRows`）。
 * 2. **模板负责的整天交回模板**：模板里被手改过、且本周没有启用专属行的天，本周**一条都不写**。
 *    一旦为这天写入专属行，`WeekPlanWeekResolver` 的逐天覆盖规则会让它不再回落模板，
 *    用户在模板里手改/删掉的动作在本周被静默绕过（"删掉的深蹲又回来了"）。
 * 3. **必须落到指定那一周**：草案的 `weekStartEpochDay` 一律取 [targetWeek]。不带这一维就会落到
 *    `0`（=「每周相同」那份），于是"给下周生成"会**偷偷改掉每周循环的那份计划**。
 * 4. **陈旧行回收由调用方显式决定**：`weekRowsForRetirement` 带上 [weekRows] 时，`commit()` 会
 *    停用"上版生成、本次不再出现"的行；外部导入传 `retireStaleRows = false` 把这一动作整个关掉。
 *    无论哪种，模板行都**绝不并进来** —— 否则整份「每周相同」会被当成陈旧 AI 行停用。
 *
 * ⚠️ 与旧实现一样：**这里没有任何 DELETE**，也不写库，只产出内存快照。
 */
object PlanDraftProjector {

    /**
     * @param proposal 已算好的草案（本地规则 / 远端 / 外部导入都先归一到这个形状）
     * @param targetWeek 目标周的周一 epochDay；`0` = 「每周相同」模板，调用方**不该**传 0
     * @param weekRows 目标周的全量行（**含软删除行**），既用于挡手改槽位，也用于陈旧行回收
     * @param templateEditedRows 「每周相同」模板里被用户手改过的行（**含软删行**）：只做保护，
     *   不参与回收（见不变量 4）
     * @param fallbackReason 透传给 [PlanPreview] 的来源补充说明；外部导入这条路恒为 `null`
     * @param retireStaleRows 采纳某天时要不要**停用**该天没再列出的旧 AI 行。
     *   内置生成为 `true`（"完整重排一周"，没列出就是不要了）；
     *   **外部导入必须 `false`**：那份文档多半只写了几天，而且外部 AI 根本看不见用户本周已有
     *   什么 —— "没列出来"在它这里不构成任何意见，拿它去停用用户的行就是毁数据。
     *   实现方式：把回收用的行快照留空，[PlanPreview.weekRowsForRetirement] 为空 → `commit()` 无可回收。
     */
    fun project(
        proposal: PlanProposal,
        targetWeek: Long,
        weekRows: List<WeekPlan>,
        templateEditedRows: List<WeekPlan>,
        fallbackReason: RemoteFallbackReason? = null,
        retireStaleRows: Boolean = true,
    ): PlanPreview {
        // 🔒 双保险：即使规则层漏判，写入前也再排除一次手改槽位（"天 × 动作"）。
        val existing: List<WeekPlan> = weekRows + templateEditedRows
        val blockedSlots: Set<Pair<Int, Long>> = existing
            .filter { plan -> plan.isUserEdited }
            .map { plan -> plan.dayOfWeek to plan.exerciseId }
            .toSet()

        // 🔒 不变量 2（P0-3 日级保护）：见类注释。
        val weekActiveDays: Set<Int> = weekRows.filter { plan -> plan.isActive }.map { plan -> plan.dayOfWeek }.toSet()
        val userOwnedDays: Set<Int> = templateEditedRows
            .map { plan -> plan.dayOfWeek }
            .filter { day -> day !in weekActiveDays }
            .toSet()

        // 「已保留 N 条」只数**用户在界面上找得着**的行。
        // 模板手改行（`week_start = 0`）与软删行同样受保护（上面两件事照旧），但它们不会
        // 出现在本周的卡片里 —— 把它们报进数字，就是"提示说保留了 4 条，用户一条都找不到"。
        val visibleEditedThisWeek: Set<Long> = weekRows
            .filter { plan -> plan.isActive && plan.isUserEdited }
            .map { plan -> plan.id }
            .toSet()

        // 🔒 不变量 1 + 3：只挑可写槽位，并统一落到 targetWeek。
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
                            // P3：落到**目标周**（不带这一维就会落到 `0` = 「每周相同」那份）。
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
            // 生成链路变成两次 `getRowsForWeek`，而"只读一次"是既有单测钉住的口径。
            weekRowsForRetirement = if (retireStaleRows) weekRows else emptyList(),
            templateOwnedDays = userOwnedDays,
            preservedCount = proposal.preservedUserEditedIds.count { id -> id in visibleEditedThisWeek },
            notes = proposal.notes,
            source = proposal.source,
            fallbackReason = fallbackReason,
            analysis = proposal.analysis,
            basis = proposal.basis,
        )
    }
}
