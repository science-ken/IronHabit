package com.ironhabit.app.data.repository

import androidx.room.withTransaction
import com.ironhabit.app.data.local.AppDatabase
import com.ironhabit.app.data.local.dao.WeekPlanDao
import com.ironhabit.app.data.mapper.PlanMapper
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.usecase.WeekPlanWeekResolver
import com.ironhabit.app.domain.util.DateUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * [PlanRepository] 的 data 层实现。
 *
 * v2 起：删除 = **软删除**（`is_active = 0` + `is_user_edited = 1`），
 * 用户新增/修改 = **显式 upsert**（禁用 `REPLACE`）并置 `isUserEdited = true`，
 * 以避免 AI 下次生成时静默撤销用户改动（架构 schema-v2 §6.3 坑 3/4/6）。
 */
@Singleton
class PlanRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val weekPlanDao: WeekPlanDao,
    private val clock: Clock,
    private val timeZone: TimeZone,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PlanRepository {

    /** 「现在」所在周的周一（P3：默认读哪一周的计划）。 */
    private fun currentWeekStart(): Long = DateUtils.weekStartMon1(DateUtils.todayEpochDay(clock, timeZone))

    override fun observePlansForDay(dayOfWeek: Int): Flow<List<WeekPlan>> =
        observeEffectivePlanForDay(dayOfWeek, currentWeekStart())

    override fun observeEffectivePlanForDay(
        dayOfWeek: Int,
        weekStartEpochDay: Long,
    ): Flow<List<WeekPlan>> =
        weekPlanDao.observeRowsForDay(dayOfWeek)
            .map { entities ->
                WeekPlanWeekResolver.effectiveForDay(
                    rowsForDay = entities.map(PlanMapper::toDomain),
                    dayOfWeek = dayOfWeek,
                    weekStartEpochDay = weekStartEpochDay,
                )
            }
            .flowOn(ioDispatcher)

    override fun observeEffectivePlanForWeek(weekStartEpochDay: Long): Flow<List<WeekPlan>> =
        weekPlanDao.observeAllIncludingInactive()
            .map { entities ->
                WeekPlanWeekResolver.effectiveForWeek(
                    rows = entities.map(PlanMapper::toDomain),
                    weekStartEpochDay = weekStartEpochDay,
                )
            }
            .flowOn(ioDispatcher)

    override fun observeRepeatPlan(): Flow<List<WeekPlan>> =
        observeEffectivePlanForWeek(WeekPlan.TEMPLATE_WEEK_START)

    override suspend fun getRowsForWeek(weekStartEpochDay: Long): List<WeekPlan> =
        weekPlanDao.getRowsForWeek(weekStartEpochDay).map(PlanMapper::toDomain)

    override suspend fun getRepeatRows(): List<WeekPlan> =
        weekPlanDao.getRepeatRows().map(PlanMapper::toDomain)

    /**
     * 勾选 / 取消「每周相同」。
     *
     * - **勾上**：把这一周的**启用行**复制成"每周相同"那份（`week_start_epoch_day = 0`）。
     *   逐个走 `upsertExplicit` —— 这样即使这个槽位上还躺着一条**早先取消时软删掉的行**，
     *   也会被"复活 + 更新"，而不是撞唯一索引（`REPLACE` 是红线，不能用）。
     * - **取消**：把"每周相同"那份整体**软停用**（`is_active = 0`，不 DELETE），
     *   于是没有自己计划的周就变成空的（界面显示「创建训练计划」）。
     *
     * @return 复制/停用的行数（`0` = 该周本来就没有自己的计划，勾选无意义）
     */
    override suspend fun setRepeatWeekly(weekStartEpochDay: Long, enabled: Boolean): Int =
        // B-17：整份复制/停用是**多行**操作 —— 包进单事务，中途失败不留"半套模板"。
        database.withTransaction {
            if (!enabled) {
                weekPlanDao.deactivateRepeatRows()
            } else {
                val weekRows: List<WeekPlan> = weekPlanDao.getRowsForWeek(weekStartEpochDay)
                    .map(PlanMapper::toDomain)
                    .filter { plan -> plan.isActive }
                if (weekRows.isEmpty()) {
                    0
                } else {
                    for (plan in weekRows) {
                        weekPlanDao.upsertExplicit(
                            PlanMapper.toEntity(
                                plan.copy(
                                    id = 0L,
                                    weekStartEpochDay = WeekPlan.TEMPLATE_WEEK_START,
                                    isActive = true,
                                    isUserEdited = false,
                                ),
                            ),
                        )
                    }
                    weekRows.size
                }
            }
        }

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
        // B-17：批量写入包进单事务 —— 中途失败整体回滚，不留"半套 AI 计划"。
        if (plans.isEmpty()) return 0
        return database.withTransaction {
            for (plan in plans) {
                weekPlanDao.upsertExplicit(PlanMapper.toEntity(plan).copy(isUserEdited = false))
            }
            plans.size
        }
    }

    /**
     * 回收陈旧 AI 行（修复 C2）：把 [plans] 对应 id 的行 `is_active = 0`。
     *
     * 只做 `UPDATE`（DAO 侧另有 `is_user_edited = 0` 兜底），**绝不 `DELETE` / `REPLACE`**。
     * id 为 `0`（尚未落库）的行跳过；空集合直接返回 `0`（避免 `IN ()` 空列表 SQL）。
     */
    override suspend fun deactivateGenerated(plans: List<WeekPlan>): Int {
        val ids: List<Long> = plans.map { it.id }.filter { it != 0L }
        if (ids.isEmpty()) return 0
        return weekPlanDao.deactivateGenerated(ids)
    }

    override fun observePlannedWeekdays(): Flow<List<Int>> =
        observePlannedWeekdays(currentWeekStart())

    override fun observePlannedWeekdays(weekStartEpochDay: Long): Flow<List<Int>> =
        observeEffectivePlanForWeek(weekStartEpochDay)
            .map { plans -> plans.map { plan -> plan.dayOfWeek }.distinct().sorted() }
            .flowOn(ioDispatcher)

    /**
     * 显式 upsert 并置 `isUserEdited = true`。
     *
     * 命中已有行（含软删行）→ `UPDATE`（保 `is_active` 等由调用方给定），未命中 → `INSERT`；
     * **绝不使用 `OnConflictStrategy.REPLACE`**，否则会重建整行冲掉 flags（§6.3 坑 4）。
     */
    override suspend fun upsert(plan: WeekPlan): Long =
        weekPlanDao.upsertExplicit(PlanMapper.toEntity(plan).copy(isUserEdited = true))

    /** 恢复为推荐：DAO 侧带 `is_active = 1` 守卫（软删行不受影响，见 [WeekPlanDao.resetToRecommended]）。 */
    override suspend fun resetToRecommended(id: Long) {
        weekPlanDao.resetToRecommended(id)
    }

    override suspend fun delete(id: Long) {
        // 软删除：保留唯一索引槽位 + 阻止 AI 复活；不影响历史打卡记录。
        weekPlanDao.softDelete(id)
    }

    override suspend fun applyWeeklyChanges(
        upserts: List<WeekPlan>,
        softDeleteIds: List<Long>,
    ): Int = database.withTransaction {
        // B-17：目标态提交的原子性 —— 全部 upsert + 软删要么都成，要么都不成。
        for (plan in upserts) {
            weekPlanDao.upsertExplicit(PlanMapper.toEntity(plan).copy(isUserEdited = true))
        }
        var removed: Int = 0
        for (id in softDeleteIds) {
            weekPlanDao.softDelete(id)
            removed++
        }
        removed
    }
}
