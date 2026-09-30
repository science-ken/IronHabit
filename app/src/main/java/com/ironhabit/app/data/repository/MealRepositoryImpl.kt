package com.ironhabit.app.data.repository

import androidx.room.withTransaction
import com.ironhabit.app.data.local.AppDatabase
import com.ironhabit.app.data.local.dao.MealDao
import com.ironhabit.app.data.local.dao.MealItemDao
import com.ironhabit.app.data.local.dto.DietTallyRaw
import com.ironhabit.app.data.local.entity.MealEntity
import com.ironhabit.app.data.mapper.MealMapper
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.DietTally
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealTotals
import com.ironhabit.app.domain.repository.MealRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * [MealRepository] 的 data 层实现。
 *
 * 软删 / upsert 语义收敛在 [MealDao]（与 v2 `PlanRepositoryImpl` 同一套 v2 红线）：
 * 删除 = 软删除（`is_active = 0` + `is_user_edited = 1`），用户编辑 = 显式 upsert 且 `isUserEdited = true`，
 * AI 生成 = 显式 upsert 且 `isUserEdited = false`（命中则保留用户勾选）。**全程无 `DELETE` / `REPLACE`**。
 */
@Singleton
class MealRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val mealDao: MealDao,
    private val mealItemDao: MealItemDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MealRepository {

    override fun observeMeals(epochDay: Long): Flow<List<Meal>> =
        mealDao.observeByDate(epochDay)
            .map { entities -> entities.map(MealMapper::toDomain) }
            .flowOn(ioDispatcher)

    override fun observeMealsIncludingInactive(epochDay: Long): Flow<List<Meal>> =
        mealDao.observeByDateIncludingInactive(epochDay)
            .map { entities -> entities.map(MealMapper::toDomain) }
            .flowOn(ioDispatcher)

    override suspend fun dietTally(todayEpochDay: Long): DietTally {
        val raw: DietTallyRaw = mealDao.dietTally(todayEpochDay)
        return DietTally(
            mealRowCount = raw.mealRowCount,
            filledMealCount = raw.filledMealCount,
            itemCount = raw.itemCount,
            kcal = raw.itemKcal,
            lastFilledEpochDay = raw.lastFilledEpochDay,
        )
    }

    override fun observeTotals(epochDay: Long): Flow<MealTotals> =
        mealDao.observeTotals(epochDay)
            .map { raw ->
                MealTotals(
                    intakeKcal = raw.intakeKcal,
                    intakeProtein = raw.intakeProtein,
                    planKcal = raw.planKcal,
                    planProtein = raw.planProtein,
                )
            }
            .flowOn(ioDispatcher)

    override suspend fun getMealsIncludingInactive(epochDay: Long): List<Meal> =
        mealDao.getByDateIncludingInactive(epochDay).map(MealMapper::toDomain)

    /**
     * AI 一次生成 4 餐 → **多行写入必须原子**。中途失败会留下"部分餐已换、部分还是旧的"，
     * 当天的热量合计就是错的，而这个错值还会被 AI 教练页当输入读回去。
     */
    override suspend fun upsertGenerated(meals: List<Meal>): Int =
        database.withTransaction {
            // 只 upsert，绝不 DELETE（含"先删当天再重建"）—— 见接口文档。
            for (meal in meals) {
                mealDao.upsertGenerated(MealMapper.toEntity(meal).copy(isUserEdited = false))
            }
            meals.size
        }

    /**
     * 用户编辑 upsert。换槽分支（目标餐次被软删行占用 → 内容搬进占位行、原行转软删）时，
     * 原餐名下的 `meal_items` 必须**跟着搬**到占位行，否则这些明细会挂在被软删的旧餐上，
     * 从当日摄入与 `dietTally` 里消失（V3 报告 新-P2）。
     *
     * 换槽分支的判别：`MealDao.upsertUser` 仅在"内容搬进另一行"时返回**不同于入参 id** 的
     * 占位行 id，其余分支（同 id 更新 / 新插入 / 槽位兜底且 `id=0`）都返回入参 id 或 `id=0`
     * 入参 —— 因此 `newId != sourceId && sourceId != 0L` 即换槽。两步收在同一个
     * `withTransaction` 里，条目迁移与餐行互换要么同时生效要么同时回滚。
     */
    override suspend fun upsert(meal: Meal): Long = database.withTransaction {
        val entity: MealEntity =
            MealMapper.toEntity(meal).copy(isUserEdited = true)
        val sourceId: Long = entity.id
        val newId: Long = mealDao.upsertUser(entity)
        if (sourceId != 0L && newId != sourceId) {
            mealItemDao.migrateAllItems(fromMealId = sourceId, toMealId = newId)
        }
        newId
    }

    override suspend fun setCompleted(id: Long, done: Boolean) {
        mealDao.setCompleted(id, done)
    }

    override suspend fun delete(id: Long) {
        // 软删除：保留唯一索引槽位 + 阻止重新生成复活。
        mealDao.softDelete(id)
    }

    override suspend fun restore(id: Long) {
        mealDao.restore(id)
    }
}
