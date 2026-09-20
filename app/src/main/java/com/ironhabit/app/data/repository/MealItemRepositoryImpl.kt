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

    override suspend fun countByMeal(mealId: Long): Int = mealItemDao.countByMeal(mealId)

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
     */
    override suspend fun moveTo(itemId: Long, targetMealId: Long) {
        val item: MealItem = mealItemDao.getById(itemId)?.let { MealItemMapper.toDomain(it) } ?: return
        val targetCount: Int = mealItemDao.countByMeal(targetMealId)
        mealItemDao.update(
            MealItemMapper.toEntity(item.copy(mealId = targetMealId, sortOrder = targetCount)),
        )
    }
}
