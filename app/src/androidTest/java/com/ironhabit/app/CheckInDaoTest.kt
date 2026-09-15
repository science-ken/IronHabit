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
        val firstId = checkIn(exerciseAId, day = 100L, sets = 3)
        val secondId = checkIn(exerciseAId, day = 100L, sets = 5)

        assertEquals(1, checkInDao.countOn(100L))
        // v2 显式 upsert：命中已有唯一槽位 → UPDATE（**非** REPLACE/DELETE+INSERT）。
        // 后写覆盖前写，且**主键 id 保持不变**（REPLACE 会重建行 id，此处已不再如此）。
        assertEquals(firstId, secondId)
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

    /**
     * 同一组连点两次 = `xor` 两次 → mask 回到原值（`0`），派生列同步归零。
     *
     * 覆盖 [CheckInDao.toggleSetBit] 的「空行自举」：行**不存在**时第一次调用要自己插入种子行，
     * 第二次调用必须命中同一行（同一事务内串行化，不会撞唯一约束）。
     */
    @Test
    fun togglingSameSetIndexTwiceRestoresOriginalMask() = runBlocking {
        val seed = seedEntity(exerciseAId, day = 100L)

        val firstMask = checkInDao.toggleSetBit(exerciseAId, 100L, setIndex = 0, seed = seed)
        val secondMask = checkInDao.toggleSetBit(exerciseAId, 100L, setIndex = 0, seed = seed)

        assertEquals("第一次勾选 → 只有 bit 0", maskOf(1), firstMask)
        assertEquals("第二次勾选 → 回到原值", 0, secondMask)

        val row = checkInDao.getForExerciseOnDate(exerciseAId, 100L)
        assertEquals("mask 必须回到 0", 0, row?.completedSetsMask)
        assertEquals("派生列与 mask 同步归零", 0, row?.completedSets)
        assertEquals("自举插入不得产生第二行", 1, checkInDao.countOn(100L))
    }

    /** 依次勾选第 1 组与第 3 组 → mask = `0b101`，派生列 `completed_sets` = 置位数 = 2。 */
    @Test
    fun togglingSetsZeroAndTwoYieldsExpectedMaskAndPopcount() = runBlocking {
        val seed = seedEntity(exerciseAId, day = 100L)

        checkInDao.toggleSetBit(exerciseAId, 100L, setIndex = 0, seed = seed)
        val mask = checkInDao.toggleSetBit(exerciseAId, 100L, setIndex = 2, seed = seed)

        assertEquals(0b101, mask)

        val row = checkInDao.getForExerciseOnDate(exerciseAId, 100L)
        assertEquals("(0,2) → 0b101", 0b101, row?.completedSetsMask)
        assertEquals("completed_sets = 置位数", 2, row?.completedSets)
        assertEquals(
            "不变量：completed_sets == completed_sets_mask.countOneBits()",
            row?.completedSetsMask?.countOneBits(),
            row?.completedSets,
        )
    }

    /**
     * 越界 `setIndex`（含负数）**静默忽略**：不抛异常、不改 mask、**不建行**
     * （域层策略：真机崩溃比一次错点严重得多）。
     */
    @Test
    fun outOfRangeSetIndexIsSilentlyIgnored() = runBlocking {
        val seed = seedEntity(exerciseAId, day = 100L)

        // 越上界（MAX_SETS = 31）+ 负数，两者都必须无声无息。
        assertEquals(0, checkInDao.toggleSetBit(exerciseAId, 100L, setIndex = 31, seed = seed))
        assertEquals(0, checkInDao.toggleSetBit(exerciseAId, 100L, setIndex = -1, seed = seed))
        assertNull("越界调用不得建行", checkInDao.getForExerciseOnDate(exerciseAId, 100L))

        // 合法调用仍然生效，证明前面的越界调用没有污染任何状态。
        checkInDao.toggleSetBit(exerciseAId, 100L, setIndex = 30, seed = seed)
        val row = checkInDao.getForExerciseOnDate(exerciseAId, 100L)
        assertEquals("bit 30", 1 shl 30, row?.completedSetsMask)
        assertEquals(1, row?.completedSets)
    }

    /** 便捷插入一条打卡记录，返回行 id（用于验证 upsert 命中时 id 稳定）。 */
    private suspend fun checkIn(exerciseId: Long, day: Long, sets: Int): Long =
        checkInDao.upsert(
            CheckInEntity(
                exerciseId = exerciseId,
                dateEpochDay = day,
                dateStartMillis = day * 86_400_000L,
                completedSets = sets,
                // 维护 v2 不变量：completed_sets == completed_sets_mask.countOneBits()
                completedSetsMask = maskOf(sets),
                completedReps = 10,
            ),
        )

    /** 由完成组数折算低 n 位全 1 的 bitmask（与 `CheckIn.maskFromCount` 口径一致）。 */
    private fun maskOf(sets: Int): Int =
        if (sets <= 0) 0 else if (sets >= 31) Int.MAX_VALUE else (1 shl sets) - 1

    /**
     * [CheckInDao.toggleSetBit] 的种子模板行：行不存在时才会被插入（mask 由 DAO 强制为 `0`）。
     *
     * 与生产路径一致：仓库层负责时间戳口径，DAO 只负责位图。
     */
    private fun seedEntity(exerciseId: Long, day: Long): CheckInEntity = CheckInEntity(
        exerciseId = exerciseId,
        dateEpochDay = day,
        dateStartMillis = day * 86_400_000L,
        completedSets = 0,
        completedSetsMask = 0,
        completedReps = 10,
    )
}
