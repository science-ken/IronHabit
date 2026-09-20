package com.ironhabit.app.data.repository

import androidx.room.withTransaction
import com.ironhabit.app.data.local.AppDatabase
import com.ironhabit.app.data.local.FoodSeeder
import com.ironhabit.app.data.local.dao.FoodDao
import com.ironhabit.app.data.mapper.FoodMapper
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.repository.FoodRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * [FoodRepository] 的 data 层实现。
 *
 * 写路径全部走 `FoodDao.upsert`（显式读改写），**不经过任何 `REPLACE`** —— 理由见 `FoodDao` 类注释。
 */
@Singleton
class FoodRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val foodDao: FoodDao,
    private val foodSeeder: FoodSeeder,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : FoodRepository {

    override fun observeActive(): Flow<List<Food>> =
        foodDao.observeActiveWithServings()
            .map { rows -> rows.map { row -> FoodMapper.toDomain(row) } }
            .flowOn(ioDispatcher)

    override suspend fun getFood(foodId: Long): Food? =
        foodDao.getWithServingsById(foodId)?.let { row -> FoodMapper.toDomain(row) }

    override suspend fun nameExists(name: String, excludeId: Long): Boolean =
        foodDao.countByName(name.trim(), excludeId) > 0

    /**
     * 主表行 + 份量行**必须同事务**。
     *
     * 分两次写会留下一个中间态：主表已更新、份量还是旧的 —— 那时"一碗"配着上一次的克数，
     * 算出来的热量是错的，而且这个错值会直接被记进 `meal_items` 的快照里**永久留下**。
     * 与 `MealRepositoryImpl.upsertGenerated` 同一个理由。
     */
    override suspend fun upsert(food: Food): Long =
        database.withTransaction {
            val (entity, servings) = FoodMapper.toEntity(food)
            val foodId: Long = foodDao.upsert(entity)
            // 整组替换而不是逐行 diff：份量最多几条，删重插的代价可以忽略，
            // 而 diff 要处理"改了单位名""改了克数""删了中间一条"三种情况，每种都是一个 bug 位。
            foodDao.deleteServings(foodId)
            if (servings.isNotEmpty()) {
                foodDao.insertServings(servings.map { serving -> serving.copy(foodId = foodId) })
            }
            foodId
        }

    /**
     * 停用主表行即可，**份量行留着**：
     * 子表是 `ON DELETE CASCADE`，但我们从不物理删主表行，所以不会被级联清掉；
     * 留着的好处是用户重新启用这条食物时，他自定义的"一碗 250g"还在。
     */
    override suspend fun deactivate(foodId: Long) {
        foodDao.deactivate(foodId)
    }

    override suspend fun seedBuiltIns(): Int = foodSeeder.seedIfNeeded()
}
