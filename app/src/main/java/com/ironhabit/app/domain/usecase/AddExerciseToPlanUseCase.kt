package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.PlanRepository
import javax.inject.Inject

/**
 * 把**一个动作**加入 / 移出某一周的训练计划（用户显式路径，动作库「+」入口的落库实现）。
 *
 * ## 语义：以 [selectedDays] 为**目标态**
 * 对 `(weekStartEpochDay, exerciseId)` 的每一个槽位（`1..7` 天）：
 * - **勾选的天** → 显式 upsert（启用 + 给定组次），**未勾选但当前启用** → 软删除
 *   （`isActive = 0 + isUserEdited = 1`，与周计划分段的删除一致，AI 不复活）；
 * - 本用例是**用户路径**：所有写入经 `PlanRepository.upsert` 强制 `isUserEdited = true` ——
 *   用户亲手排的课，AI 生成时整行跳过（红线「用户手改行永不被覆盖 / 回收」）；
 * - 用户重新勾上自己先前删掉的天 = **用户反悔**，允许复活 ——
 *   红线防的是 **AI 生成路径**复活用户删除行，不限制用户本人的显式操作；
 * - **全程无 `DELETE` / `REPLACE`**：写入走显式 upsert（软删行占着槽位则复活该行），红线 3。
 *
 * ## 空周预处理（防止"加一个动作挤没整周课"）
 * P3 的解析规则是**整周为单位**：某周有启用专属行就整周用专属，否则回落「每周相同」。
 * 若目标周目前**没有任何启用专属行**而「每周相同」里有课，只写勾选天会让该周从"回落模板"
 * 切换成"整周专属" —— 模板里**其他天**的课会全部从界面上消失。
 * 因此：先把「每周相同」的启用行**原样复制**成该周专属行（`isUserEdited` 随源行保留），
 * 再写目标天 —— 用户的直觉是"加一个动作，别的课别动"，这里替他把整周接住。
 *
 * ## 「每周都加」（[alsoRepeatWeekly]）
 * 勾选时对「每周相同」那份（`weekStartEpochDay = TEMPLATE_WEEK_START`）做**同样的目标态写入**
 * （只动本动作的槽位，不碰那份里的其他动作）—— 以后没单独排计划的周都会有它。
 *
 * @param exerciseId 动作 id
 * @param weekStartEpochDay 目标周的周一（**必带**，坑 1：不带会写进「每周相同」那份）
 * @param selectedDays 目标态：该动作应出现在 `1..7` 的哪几天
 * @param targetSets 组数（越界自动钳制到 [InputLimits] 口径）
 * @param targetReps 次数（同上）
 * @param alsoRepeatWeekly 是否同步写入「每周相同」那份
 * @return 写入 / 移除的星期与复制的模板行数（Snackbar 文案与测试断言用）
 */
class AddExerciseToPlanUseCase @Inject constructor(
    private val planRepository: PlanRepository,
) {

    data class Result(
        /** 本次 upsert（含复活软删行）的星期，升序。 */
        val writtenDays: List<Int>,
        /** 本次软删除的星期，升序。 */
        val removedDays: List<Int>,
        /** 空周预处理时从「每周相同」复制来的行数（`0` = 未触发）。 */
        val copiedRepeatRows: Int,
    )

    suspend operator fun invoke(
        exerciseId: Long,
        weekStartEpochDay: Long,
        selectedDays: Set<Int>,
        targetSets: Int,
        targetReps: Int,
        alsoRepeatWeekly: Boolean,
    ): Result {
        val days: Set<Int> = selectedDays.filter { it in MIN_DAY..MAX_DAY }.toSet()
        val sets: Int = targetSets.coerceIn(InputLimits.MIN_SETS, InputLimits.MAX_SETS)
        val reps: Int = targetReps.coerceIn(InputLimits.MIN_REPS, InputLimits.MAX_REPS)

        val written = mutableSetOf<Int>()
        val removed = mutableSetOf<Int>()
        var copied = 0

        // B-17：先在内存里算出**完整目标态**（复制行 + upsert 行 + 待软删行），
        // 再通过 [PlanRepository.applyWeeklyChanges] **单事务**一次提交 ——
        // 中途失败整体回滚，绝不留下"复制了模板却没写上目标天"的半套状态。
        val upserts = mutableListOf<WeekPlan>()
        val softDeleteIds = mutableListOf<Long>()

        for (week in buildList {
            add(weekStartEpochDay)
            if (alsoRepeatWeekly && weekStartEpochDay != WeekPlan.TEMPLATE_WEEK_START) {
                add(WeekPlan.TEMPLATE_WEEK_START)
            }
        }) {
            var weekRows: List<WeekPlan> = planRepository.getRowsForWeek(week)

            // 空周预处理：只对"真实的专属周"做（模板份本身就是源，复制自己没有意义）。
            if (week != WeekPlan.TEMPLATE_WEEK_START &&
                weekRows.none { it.isActive } &&
                days.isNotEmpty()
            ) {
                val repeatRows: List<WeekPlan> = planRepository.getRowsForWeek(
                    WeekPlan.TEMPLATE_WEEK_START,
                ).filter { it.isActive }
                for (row in repeatRows) {
                    upserts += row.copy(
                        id = 0L,
                        weekStartEpochDay = weekStartEpochDay,
                        isActive = true,
                    )
                }
                copied += repeatRows.size
                // 复制行**本地推演**进周状态（与事务提交后的真实状态一致），
                // 下面的目标态判定按推演后的最新状态进行，无需真实重取。
                weekRows = weekRows + upserts.filter {
                    it.weekStartEpochDay == weekStartEpochDay
                }
            }

            for (day in MIN_DAY..MAX_DAY) {
                val existing: WeekPlan? = weekRows.firstOrNull {
                    it.dayOfWeek == day && it.exerciseId == exerciseId
                }
                if (day in days) {
                    upserts += WeekPlan(
                        id = existing?.id ?: 0L,
                        exerciseId = exerciseId,
                        dayOfWeek = day,
                        targetSets = sets,
                        targetReps = reps,
                        targetWeightKg = existing?.targetWeightKg,
                        targetDurationMin = existing?.targetDurationMin,
                        sortOrder = existing?.sortOrder ?: nextSortOrder(weekRows, day),
                        isActive = true,
                        isUserEdited = true, // 由 applyWeeklyChanges 统一强制，这里显式写出意图
                        weekStartEpochDay = week,
                    )
                    written += day
                } else if (existing != null && existing.isActive) {
                    // 未勾选但当前启用 → 用户取消了这一天 → 软删除（不 DELETE）。
                    softDeleteIds += existing.id
                    removed += day
                }
            }
        }

        planRepository.applyWeeklyChanges(upserts = upserts, softDeleteIds = softDeleteIds)

        return Result(
            writtenDays = written.sorted(),
            removedDays = removed.sorted(),
            copiedRepeatRows = copied,
        )
    }

    /** 该天现有启用行的最大 `sortOrder + 1`（新动作排在当天末尾）；无行时 `0`。 */
    private fun nextSortOrder(weekRows: List<WeekPlan>, day: Int): Int =
        weekRows.filter { it.dayOfWeek == day && it.isActive }
            .maxOfOrNull { it.sortOrder }?.plus(1) ?: 0

    private companion object {
        /** 星期下界（周一）。 */
        const val MIN_DAY: Int = 1

        /** 星期上界（周日）。 */
        const val MAX_DAY: Int = 7
    }
}
