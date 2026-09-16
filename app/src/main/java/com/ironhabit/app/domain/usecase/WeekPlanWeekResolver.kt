package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.WeekPlan

/**
 * **某一天的生效计划** = 该周的"专属行"优先，没有就回落"模板行"（P3，纯函数）。
 *
 * 为什么要有它：`week_plans` 一张表里同时装着两种东西 ——
 * - **模板行**（`weekStartEpochDay == WeekPlan.TEMPLATE_WEEK_START`）：每周循环，也就是这个 App
 *   从 v1 起的行为（"我每周一都这样练"）；
 * - **某周专属行**（`weekStartEpochDay == 那一周的周一`）：只对这一周有效（"下周三我把深蹲换成臀桥"）。
 *
 * 取计划时的规则（**整周为一个单位，不做"逐条混合"**）：
 * 1. 该周有**任何**启用状态的专属行 → 这一天就用专属行（哪怕这一天恰好没有专属行 → 这一天是**空的**，
 *    因为"我这周整体换了一套安排"是用户的明确意思，混着模板会排出他没安排的组合）；
 * 2. 该周完全没有专属行 → 用模板行（行为与 P3 之前一模一样）；
 * 3. 只返回**启用**行（`isActive = true`；软删除行是"用户删掉的槽位"，永远不参与）；
 * 4. 排序：`sortOrder` 升序，再按 `id` 升序（保证同输入同输出）。
 *
 * ⚠️ 纯函数：不碰仓库、不读时间、零随机 → 可 JVM 单测。仓库只负责把候选行捞出来喂给它。
 */
object WeekPlanWeekResolver {

    /**
     * @param rowsForDay 该 `dayOfWeek` 的**全部**行（模板 + 各周专属；**含软删除行**，
     *   由本函数过滤 —— 调用方不必也不该先筛，避免两处规则不一致）
     * @param dayOfWeek 星期，`1` = 周一 … `7` = 周日
     * @param weekStartEpochDay 目标周的周一（`LocalDate.toEpochDays()` 口径）
     * @return 生效的计划行（已排序、已过滤）
     */
    fun effectiveForDay(
        rowsForDay: List<WeekPlan>,
        dayOfWeek: Int,
        weekStartEpochDay: Long,
    ): List<WeekPlan> {
        val candidates: List<WeekPlan> = rowsForDay.filter { row ->
            row.dayOfWeek == dayOfWeek && row.isActive
        }
        val override: List<WeekPlan> = candidates.filter { row ->
            !row.isTemplate && row.weekStartEpochDay == weekStartEpochDay
        }
        // 整周为单位：有专属就整周用专属（哪怕这一天没有专属行 → 这一天是空的）。
        val effective: List<WeekPlan> = if (hasAnyOverrideInWeek(rowsForDay, weekStartEpochDay)) {
            override
        } else {
            candidates.filter { row -> row.isTemplate }
        }
        return effective.sortedWith(compareBy({ row -> row.sortOrder }, { row -> row.id }))
    }

    /**
     * 这一周有没有"专属安排"（不限定星期）。
     *
     * 界面要用它决定提示语：整周是"本周专属"还是"模板（每周循环）"。
     */
    fun hasAnyOverrideInWeek(rows: List<WeekPlan>, weekStartEpochDay: Long): Boolean =
        rows.any { row ->
            row.isActive && !row.isTemplate && row.weekStartEpochDay == weekStartEpochDay
        }

    /**
     * 该周的所有生效行（**不分星期**，按 星期 / sortOrder / id 排序）。
     *
     * 生成"下周计划预览"时用它：AI 返回的是整周，界面要一次看全。
     */
    fun effectiveForWeek(rows: List<WeekPlan>, weekStartEpochDay: Long): List<WeekPlan> {
        val useOverride: Boolean = hasAnyOverrideInWeek(rows, weekStartEpochDay)
        return rows
            .filter { row ->
                row.isActive &&
                    if (useOverride) {
                        !row.isTemplate && row.weekStartEpochDay == weekStartEpochDay
                    } else {
                        row.isTemplate
                    }
            }
            .sortedWith(
                compareBy(
                    { row -> row.dayOfWeek },
                    { row -> row.sortOrder },
                    { row -> row.id },
                ),
            )
    }
}
