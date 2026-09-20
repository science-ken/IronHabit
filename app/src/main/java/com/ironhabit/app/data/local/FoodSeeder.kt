package com.ironhabit.app.data.local

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.ironhabit.app.data.local.dao.FoodDao
import com.ironhabit.app.data.mapper.FoodMapper
import com.ironhabit.app.data.preset.BuiltInFoods
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.Food
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * 内置食物播种器：读 `assets/foods.json` → 解析 → 落库。
 *
 * ## 与 `DatabaseSeeder`（动作）的一处**有意差异**：这里没有"只补空"合并
 * 动作那边需要补空，是因为 v7 给 `exercises` **新加了 `equipment` 列** ——
 * 老库里已有的同名行拿不到新标注，必须往空白字段里回填。
 * 食物是新表，preset 里**没有一个字段是"可能后来才加的"**，
 * 所以"同名已存在"就直接跳过：既不需要合并，也不该合并
 * （用户自己建了一条「米饭」，我们没有任何理由去改它的任何一个数字）。
 *
 * ## 失败口径
 * assets 读不到 / JSON 解析失败 / 忌口标签不认识 → **记日志并放弃本次播种**，绝不抛到启动链路上。
 * 理由与动作播种一致：启动时炸一次，用户看到的是"app 打不开"，比"食物库空着"严重得多。
 * 但日志必须是 `Log.e` + 带异常，让这类问题在真机走查时能被看见而不是静默存在。
 */
@Singleton
class FoodSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val foodDao: FoodDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** 本次**新插入**的食物条数（同名已存在的不动、不计）。 */
    suspend fun seedIfNeeded(): Int = withContext(ioDispatcher) {
        val presets: List<Food> = try {
            val raw: String = context.assets.open(BuiltInFoods.ASSET_NAME)
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> reader.readText() }
            BuiltInFoods.toDomain(BuiltInFoods.parse(raw))
        } catch (throwable: Throwable) {
            Log.e(TAG, "内置食物库读取/解析失败，本次跳过播种", throwable)
            return@withContext 0
        }

        if (presets.isEmpty()) {
            Log.w(TAG, "内置食物库为空，跳过播种")
            return@withContext 0
        }

        database.withTransaction {
            var inserted = 0
            for (food in presets) {
                if (foodDao.getByName(food.name) != null) continue
                val (entity, servings) = FoodMapper.toEntity(food)
                val foodId: Long = foodDao.insert(entity)
                if (servings.isNotEmpty()) {
                    foodDao.insertServings(servings.map { serving -> serving.copy(foodId = foodId) })
                }
                inserted++
            }
            Log.i(TAG, "内置食物库播种完成：新增 $inserted / 共 ${presets.size} 条")
            inserted
        }
    }

    private companion object {
        const val TAG: String = "FoodSeeder"
    }
}
