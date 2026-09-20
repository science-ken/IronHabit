package com.ironhabit.app.data.repository

import androidx.room.withTransaction
import com.ironhabit.app.data.local.AppDatabase
import com.ironhabit.app.data.local.dao.MealDao
import com.ironhabit.app.data.mapper.MealMapper
import com.ironhabit.app.di.IoDispatcher
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MealRepository {

    override fun observeMeals(epochDay: Long): Flow<List<Meal>> =
        mealDao.observeByDate(epochDay)
            .map { entities -> entities.map(MealMapper::toDomain) }
            .flowOn(ioDispatcher)

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

    override suspend fun upsert(meal: Meal): Long =
        mealDao.upsertUser(MealMapper.toEntity(meal).copy(isUserEdited = true))

    override suspend fun setCompleted(id: Long, done: Boolean) {
        mealDao.setCompleted(id, done)
    }

    override suspend fun delete(id: Long) {
        // 软删除：保留唯一索引槽位 + 阻止重新生成复活。
        mealDao.softDelete(id)
    }
}
