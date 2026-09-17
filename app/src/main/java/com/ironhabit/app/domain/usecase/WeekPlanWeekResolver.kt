package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.WeekPlan

/**
 * **某一天的生效计划**（P3 + **逐天覆盖**语义，纯函数）。
 *
 * 为什么要有它：`week_plans` 一张表里同时装着两种东西 ——
 * - **模板行**（`weekStartEpochDay == WeekPlan.TEMPLATE_WEEK_START`）：每周循环，也就是这个 App
 *   从 v1 起的行为（"我每周一都这样练"）；
 * - **某周专属行**（`weekStartEpochDay == 那一周的周一`）：只对这一周有效（"下周三我把深蹲换成臀桥"）。
 *
 * ## 逐天覆盖语义（全工程唯一口径，修复 B-1）
 * 给定某天，判定规则**只看这一天自己的行**：
 * 1. 该周的专属启用行非空 → 用它们；
 * 2. 否则回落「每周相同」的模板启用行；
 * 3. 两者皆无 → 空列表（休息日）。
 *
 * ⚠️ 历史教训（B-1）：旧实现是「整周为一个单位」—— 周内任何一天有专属，其余天就整体变空。
 * 整周判定需要"看到整周"的行，而日视图只喂了当天行 → 判定退化成按天，与周视图（真·整周判定）
 * 对同一周给出矛盾结论。逐天覆盖下每一天独立判定，日/周视图**共用同一个函数**，永不打架。
 * 「整周换一套」依然可行：把整周写满专属行（AI 生成 / 复制模板 / 逐天手动排）即可；
 * 之后删掉某天的专属行，那一天自动回落模板 —— 这正是逐天覆盖的本意。
 *
 * 规则细则：
 * - 只返回**启用**行（`isActive = true`；软删除行是"用户删掉的槽位"，永远不参与）；
 * - 排序：`sortOrder` 升序，再按 `id` 升序（保证同输入同输出）；
 * - ⚠️ 纯函数：不碰仓库、不读时间、零随机 → 可 JVM 单测。仓库只负责把候选行捞出来喂给它。
 */
object WeekPlanWeekResolver {

    /**
     * @param rowsForDay 候选行（建议**全量**：模板 + 各周专属、含软删行均可，
     *   本函数按 `dayOfWeek` / `isActive` 自行过滤 —— 日/周视图喂同一份全量数据即天然一致）
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
        // ① 该周的专属启用行（含 AI 行与用户手改行）
        val override: List<WeekPlan> = candidates.filter { row ->
            !row.isTemplate && row.weekStartEpochDay == weekStartEpochDay
        }
        if (override.isNotEmpty()) {
            return override.sortedWith(compareBy({ row -> row.sortOrder }, { row -> row.id }))
        }
        // ② 回落「每周相同」模板启用行；③ 都没有 → 休息日（空列表）
        return candidates.filter { row -> row.isTemplate }
            .sortedWith(compareBy({ row -> row.sortOrder }, { row -> row.id }))
    }

    /**
     * 该周的所有生效行（**不分星期**，按 星期 / sortOrder / id 排序）。
     *
     * 生成"下周计划预览"时用它：AI 返回的是整周，界面要一次看全。
     * 实现上**逐天复用 [effectiveForDay]** —— 判定规则只有一份，日/周视图永不打架。
     *
     * @param rows 全量候选行（所有周、所有天、含软删行均可；内部自行过滤）
     */
    fun effectiveForWeek(rows: List<WeekPlan>, weekStartEpochDay: Long): List<WeekPlan> =
        (1..7).flatMap { day ->
            effectiveForDay(
                rowsForDay = rows,
                dayOfWeek = day,
                weekStartEpochDay = weekStartEpochDay,
            )
        }
}
