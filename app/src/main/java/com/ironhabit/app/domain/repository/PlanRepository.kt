package com.ironhabit.app.domain.repository

import com.ironhabit.app.domain.model.WeekPlan
import kotlinx.coroutines.flow.Flow

/**
 * 周计划仓库接口。
 */
interface PlanRepository {

    /** 观察某星期（`1..7`）的启用计划条目；联表过滤已停用动作。 */
    fun observePlansForDay(dayOfWeek: Int): Flow<List<WeekPlan>>

    /** 观察全部启用计划条目（按 `dayOfWeek`、`sortOrder` 升序）。 */
    fun observeAll(): Flow<List<WeekPlan>>

    /**
     * 观察**全部**计划条目（**含 `isActive == false` 的软删除行**）。
     *
     * 🔒 **AI 生成前必须拿全量**：软删除行仍占 `UNIQUE(day_of_week, exercise_id)` 槽位，
     * 若只看启用行就会误往该槽位写入 → **把用户删掉的那条"复活"**。
     * 纯新增，[observeAll] 的语义一字未改。
     */
    fun observeAllIncludingInactive(): Flow<List<WeekPlan>>

    /**
     * **AI 生成入口**：对"可写槽位"做**显式 upsert**，并置 `isUserEdited = false`。
     *
     * 与用户手动入口 [upsert] 的区别**仅在** `isUserEdited`：
     * 手动入口置 `true`（保护用户改动），本入口置 `false`（交还 AI 接管）。
     *
     * 🔒 本方法**只做 upsert，绝不做任何 DELETE**（含"先删本周再重建"）——
     * 写入前由 UseCase 排除手改槽位，本方法不再二次过滤。
     *
     * @return 实际写入的条数
     */
    suspend fun upsertGenerated(plans: List<WeekPlan>): Int

    /** 观察「有计划的日子」（`1..7`，升序）→ 预览里的 chip 行。 */
    fun observePlannedWeekdays(): Flow<List<Int>>

    /**
     * 新增或更新计划条目（用户操作入口），返回行 id。
     *
     * 采用**显式 upsert**（命中 UPDATE / 未命中 INSERT，**禁用 REPLACE**）并置
     * `isUserEdited = true`，避免 AI 下次生成时静默撤销用户改动（架构 §6.3 坑 3/4/6）。
     */
    suspend fun upsert(plan: WeekPlan): Long

    /** 恢复为 AI 推荐（`isUserEdited = false`），交还 AI 接管。 */
    suspend fun resetToRecommended(id: Long)

    /** 软删除计划条目（`isActive = false` + `isUserEdited = true`），**不影响历史打卡记录**。 */
    suspend fun delete(id: Long)
}
