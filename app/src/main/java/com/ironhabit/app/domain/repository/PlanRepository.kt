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

    /** 新增或更新计划条目，返回行 id。 */
    suspend fun upsert(plan: WeekPlan): Long

    /** 删除计划条目（**不**影响历史打卡记录）。 */
    suspend fun delete(id: Long)
}
