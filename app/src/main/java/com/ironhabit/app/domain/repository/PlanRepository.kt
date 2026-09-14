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
