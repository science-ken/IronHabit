package com.ironhabit.app.data.repository

import com.ironhabit.app.data.local.dao.WeekPlanDao
import com.ironhabit.app.data.mapper.PlanMapper
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.PlanRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * [PlanRepository] 的 data 层实现。
 *
 * v2 起：删除 = **软删除**（`is_active = 0` + `is_user_edited = 1`），
 * 用户新增/修改 = **显式 upsert**（禁用 `REPLACE`）并置 `isUserEdited = true`，
 * 以避免 AI 下次生成时静默撤销用户改动（架构 schema-v2 §6.3 坑 3/4/6）。
 */
@Singleton
class PlanRepositoryImpl @Inject constructor(
    private val weekPlanDao: WeekPlanDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PlanRepository {

    override fun observePlansForDay(dayOfWeek: Int): Flow<List<WeekPlan>> =
        weekPlanDao.observeActiveByDay(dayOfWeek)
            .map { entities -> entities.map(PlanMapper::toDomain) }
            .flowOn(ioDispatcher)

    override fun observeAll(): Flow<List<WeekPlan>> =
        weekPlanDao.observeAll()
            .map { entities -> entities.map(PlanMapper::toDomain) }
            .flowOn(ioDispatcher)

    override fun observeAllIncludingInactive(): Flow<List<WeekPlan>> =
        weekPlanDao.observeAllIncludingInactive()
            .map { entities -> entities.map(PlanMapper::toDomain) }
            .flowOn(ioDispatcher)

    override suspend fun upsertGenerated(plans: List<WeekPlan>): Int {
        // 只 upsert，绝不 DELETE（含"先删本周再重建"）—— 见接口文档。
        for (plan in plans) {
            weekPlanDao.upsertExplicit(PlanMapper.toEntity(plan).copy(isUserEdited = false))
        }
        return plans.size
    }

    override fun observePlannedWeekdays(): Flow<List<Int>> =
        weekPlanDao.observePlannedWeekdays().flowOn(ioDispatcher)

    /**
     * 显式 upsert 并置 `isUserEdited = true`。
     *
     * 命中已有行（含软删行）→ `UPDATE`（保 `is_active` 等由调用方给定），未命中 → `INSERT`；
     * **绝不使用 `OnConflictStrategy.REPLACE`**，否则会重建整行冲掉 flags（§6.3 坑 4）。
     */
    override suspend fun upsert(plan: WeekPlan): Long =
        weekPlanDao.upsertExplicit(PlanMapper.toEntity(plan).copy(isUserEdited = true))

    override suspend fun resetToRecommended(id: Long) {
        weekPlanDao.resetToRecommended(id)
    }

    override suspend fun delete(id: Long) {
        // 软删除：保留唯一索引槽位 + 阻止 AI 复活；不影响历史打卡记录。
        weekPlanDao.softDelete(id)
    }
}
