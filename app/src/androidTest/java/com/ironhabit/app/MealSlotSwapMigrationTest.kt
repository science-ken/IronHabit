package com.ironhabit.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ironhabit.app.data.local.AppDatabase
import com.ironhabit.app.data.local.entity.MealEntity
import com.ironhabit.app.data.local.entity.MealItemEntity
import com.ironhabit.app.data.repository.MealRepositoryImpl
import com.ironhabit.app.domain.model.Meal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 编辑一餐**换餐次槽位**时 `meal_items` 必须跟着搬（V3 报告 新-P2）：
 *
 * `MealDao.upsertUser` 的换槽分支把内容搬进软删占位行并软删原行，若条目不迁移，
 * 就会挂在被软删的旧餐上，从当日摄入（`is_active = 1` JOIN）与 `dietTally` 里消失。
 * 走 [MealRepositoryImpl.upsert] 全链路验证，判别式 `newId != sourceId` 也一并覆盖。
 */
@RunWith(AndroidJUnit4::class)
class MealSlotSwapMigrationTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: MealRepositoryImpl
    private var day: Long = 20_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MealRepositoryImpl(
            database = database,
            mealDao = database.mealDao(),
            mealItemDao = database.mealItemDao(),
            ioDispatcher = Dispatchers.Default,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertMeal(
        mealType: String,
        isActive: Boolean = true,
        sortOrder: Int = 0,
    ): Long = database.mealDao().insert(
        MealEntity(
            dateEpochDay = day,
            mealType = mealType,
            itemsText = "条目文本",
            kcal = 100,
            sortOrder = sortOrder,
            isActive = isActive,
            isUserEdited = true,
        )
    )

    private suspend fun insertItem(mealId: Long, name: String, sortOrder: Int): Long =
        database.mealItemDao().insert(
            MealItemEntity(
                mealId = mealId,
                foodName = name,
                grams = 100.0,
                kcal = 50,
                proteinG = 5.0,
                carbsG = 5.0,
                fatG = 5.0,
                sortOrder = sortOrder,
            )
        )

    @Test
    fun slotSwapCarriesItemsToOccupantRow() = runBlocking {
        // 原餐：午餐（生效，带两条明细）；目标槽位：晚餐（软删占位，带一条旧明细）。
        val lunchId = insertMeal("LUNCH", isActive = true, sortOrder = 1)
        val deletedDinnerId = insertMeal("DINNER", isActive = false, sortOrder = 3)
        insertItem(lunchId, "米饭", sortOrder = 0)
        insertItem(lunchId, "鸡胸肉", sortOrder = 1)
        insertItem(deletedDinnerId, "旧晚餐残留", sortOrder = 0)

        // 把午餐编辑成晚餐 → 走换槽分支。
        val newId: Long = repository.upsert(
            Meal(
                id = lunchId,
                dateEpochDay = day,
                mealType = com.ironhabit.app.domain.model.MealType.DINNER,
                items = listOf("米饭", "鸡胸肉"),
                kcal = 300,
                proteinG = 30.0,
                isCompleted = true,
            )
        )

        // 返回的是占位行 id（换槽判别式的依据）。
        assertEquals(deletedDinnerId, newId)

        // 原行已软删；占位行已复活并携带新内容。
        val oldRow = database.mealDao().getById(lunchId)!!
        val newRow = database.mealDao().getById(newId)!!
        assertFalse(oldRow.isActive)
        assertTrue(newRow.isActive)
        assertEquals(300, newRow.kcal)

        // 明细整体迁到新餐：旧残留排最前（sortOrder=0），原两条追加在后（1、2）。
        val migrated = database.mealItemDao().getByMeal(newId)
        assertEquals(listOf("旧晚餐残留", "米饭", "鸡胸肉"), migrated.map { it.foodName })
        assertEquals(listOf(0, 1, 2), migrated.map { it.sortOrder })
        assertEquals(0, database.mealItemDao().getByMeal(lunchId).size)

        // 当日可见条目不丢（`observeByDate` 的 `is_active = 1` JOIN 口径）。
        val visibleNames = database.mealItemDao().observeByDate(day).first().map { it.foodName }
        assertEquals(listOf("旧晚餐残留", "米饭", "鸡胸肉"), visibleNames)
    }

    @Test
    fun sameSlotEditKeepsItemsInPlace() = runBlocking {
        val lunchId = insertMeal("LUNCH", isActive = true, sortOrder = 1)
        insertItem(lunchId, "米饭", sortOrder = 0)

        // 不换餐次的普通编辑：不得触发迁移（明细仍挂原行、序号不变）。
        val newId: Long = repository.upsert(
            Meal(
                id = lunchId,
                dateEpochDay = day,
                mealType = com.ironhabit.app.domain.model.MealType.LUNCH,
                items = listOf("米饭", "加个蛋"),
                kcal = 350,
                proteinG = 25.0,
                isCompleted = false,
            )
        )

        assertEquals(lunchId, newId)
        val items = database.mealItemDao().getByMeal(lunchId)
        assertEquals(1, items.size)
        assertEquals("米饭", items.single().foodName)
        assertEquals(0, items.single().sortOrder)
    }
}
