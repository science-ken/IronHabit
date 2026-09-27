package com.ironhabit.app.data.repository

import com.ironhabit.app.data.local.dao.MealItemDao
import com.ironhabit.app.data.mapper.MealItemMapper
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.MealItem
import com.ironhabit.app.domain.repository.MealItemRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * [MealItemRepository] 的 data 层实现。
 *
 * 写路径一律走 `MealItemDao.upsert`（显式读改写），**不经过 `REPLACE`** ——
 * 理由见 `MealItemDao` 类注释。
 */
@Singleton
class MealItemRepositoryImpl @Inject constructor(
    private val mealItemDao: MealItemDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MealItemRepository {

    override fun observeByDate(epochDay: Long): Flow<List<MealItem>> =
        mealItemDao.observeByDate(epochDay)
            .map { entities -> entities.map { entity -> MealItemMapper.toDomain(entity) } }
            .flowOn(ioDispatcher)

    override suspend fun getByDate(epochDay: Long): List<MealItem> =
        mealItemDao.getByDate(epochDay).map { entity -> MealItemMapper.toDomain(entity) }

    override suspend fun countByMeal(mealId: Long): Int = mealItemDao.countByMeal(mealId)

    override suspend fun getById(itemId: Long): MealItem? =
        mealItemDao.getById(itemId)?.let { entity -> MealItemMapper.toDomain(entity) }

    override suspend fun upsert(item: MealItem): Long =
        mealItemDao.upsert(MealItemMapper.toEntity(item))

    override suspend fun delete(itemId: Long) {
        mealItemDao.deleteById(itemId)
    }

    /**
     * 挪餐后把 `sort_order` 排到目标餐末尾。
     *
     * 不这么做的话条目会带着原餐的序号过来，可能插到中间、也可能和已有的撞号，
     * 列表顺序就变得不可预期。
     *
     * 取序号与改归属两步都在 [MealItemDao.moveToMealAtEnd] 的事务里 ——
     * 旧写法在这里分三条语句做（读行 → 数目标餐 → 整行写回），两次快速挪餐会读到
     * 同一个序号，还会把这一行别的列按陈旧快照写回去（审查报告 P3-2）。
     * 行已经不在了（比如另一根手指刚删掉它）就自然影响 0 行，不需要先读一次确认。
     */
    override suspend fun moveTo(itemId: Long, targetMealId: Long) {
        mealItemDao.moveToMealAtEnd(id = itemId, targetMealId = targetMealId)
    }
}
