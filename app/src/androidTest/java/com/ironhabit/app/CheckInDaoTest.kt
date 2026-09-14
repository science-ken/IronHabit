package com.ironhabit.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ironhabit.app.data.local.AppDatabase
import com.ironhabit.app.data.local.dao.CheckInDao
import com.ironhabit.app.data.local.entity.CheckInEntity
import com.ironhabit.app.data.local.entity.ExerciseEntity
import com.ironhabit.app.domain.model.ExerciseCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [CheckInDao] 内存库行为测试：upsert 幂等 / 区间查询 / 去重天数降序 / 删除 / 全量排序。
 *
 * suspend / Flow 均用 [runBlocking] 驱动（Flow 用 `.first()` 取单次快照）。
 */
@RunWith(AndroidJUnit4::class)
class CheckInDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var checkInDao: CheckInDao
    private var exerciseAId: Long = 0L
    private var exerciseBId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        checkInDao = database.checkInDao()

        // 打卡表外键指向 exercises —— 先建两个动作供关联。
        exerciseAId = database.exerciseDao().upsert(
            ExerciseEntity(name = "卧推", category = ExerciseCategory.STRENGTH),
        )
        exerciseBId = database.exerciseDao().upsert(
            ExerciseEntity(name = "跑步", category = ExerciseCategory.CARDIO),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertIsIdempotentOnSameExerciseAndDay() = runBlocking {
        checkIn(exerciseAId, day = 100L, sets = 3)
        checkIn(exerciseAId, day = 100L, sets = 5)

        assertEquals(1, checkInDao.countOn(100L))
        // REPLACE 语义：后写覆盖前写。
        assertEquals(5, checkInDao.getForExerciseOnDate(exerciseAId, 100L)?.completedSets)
    }

    @Test
    fun observeBetweenReturnsOnlyRowsWithinClosedRange() = runBlocking {
        checkIn(exerciseAId, day = 100L, sets = 1)
        checkIn(exerciseAId, day = 101L, sets = 2)
        checkIn(exerciseBId, day = 102L, sets = 3)
        checkIn(exerciseBId, day = 103L, sets = 4)

        val between = checkInDao.observeBetween(101L, 102L).first()

        assertEquals(2, between.size)
        assertEquals(listOf(101L, 102L), between.map { it.dateEpochDay }.sorted())
    }

    @Test
    fun observeActiveDaysSinceReturnsDistinctDaysDescending() = runBlocking {
        checkIn(exerciseAId, day = 100L, sets = 1)
        checkIn(exerciseBId, day = 100L, sets = 1) // 同一天、不同动作
        checkIn(exerciseAId, day = 101L, sets = 1)
        checkIn(exerciseAId, day = 99L, sets = 1) // 早于起点，应被排除

        val days = checkInDao.observeActiveDaysSince(100L).first()

        assertEquals(listOf(101L, 100L), days)
    }

    @Test
    fun deleteOnRemovesOnlyTheTargetedRow() = runBlocking {
        checkIn(exerciseAId, day = 100L, sets = 3)
        checkIn(exerciseBId, day = 100L, sets = 3)

        checkInDao.deleteOn(exerciseAId, 100L)

        assertNull(checkInDao.getForExerciseOnDate(exerciseAId, 100L))
        assertEquals(3, checkInDao.getForExerciseOnDate(exerciseBId, 100L)?.completedSets)
        assertEquals(1, checkInDao.countOn(100L))
    }

    @Test
    fun getAllReturnsRowsOrderedByDateAscending() = runBlocking {
        checkIn(exerciseAId, day = 102L, sets = 1)
        checkIn(exerciseBId, day = 100L, sets = 1)
        checkIn(exerciseAId, day = 101L, sets = 1)

        val all = checkInDao.getAll()

        assertEquals(listOf(100L, 101L, 102L), all.map { it.dateEpochDay })
    }

    /** 便捷插入一条打卡记录。 */
    private suspend fun checkIn(exerciseId: Long, day: Long, sets: Int) {
        checkInDao.upsert(
            CheckInEntity(
                exerciseId = exerciseId,
                dateEpochDay = day,
                dateStartMillis = day * 86_400_000L,
                completedSets = sets,
                completedReps = 10,
            ),
        )
    }
}
