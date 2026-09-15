package com.ironhabit.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ironhabit.app.data.local.AppDatabase
import com.ironhabit.app.data.local.entity.CheckInEntity
import com.ironhabit.app.data.local.entity.ExerciseEntity
import com.ironhabit.app.data.local.entity.HabitEntity
import com.ironhabit.app.data.local.entity.HabitLogEntity
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.HabitFrequency
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [AppDatabase] 内存库集成测试：建库、7 个 DAO 可访问、两张表唯一约束生效。
 *
 * 注：本工程 androidTest 未引入 `room-testing`（不新增依赖），因此**不做** MigrationTestHelper，
 * 只做建库与行为测试；suspend 方法统一用 [runBlocking] 调用（最稳，不依赖 coroutines-test）。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun databaseOpensAndAllDaosAreAccessible() {
        assertNotNull(database.exerciseDao())
        assertNotNull(database.weekPlanDao())
        assertNotNull(database.checkInDao())
        assertNotNull(database.habitDao())
        assertNotNull(database.habitLogDao())
        assertNotNull(database.bodyMetricDao())
        assertNotNull(database.statsDao())
    }

    @Test
    fun checkInUniqueConstraintMakesUpsertIdempotent() = runBlocking {
        val exerciseId = database.exerciseDao().upsert(
            ExerciseEntity(
                name = "深蹲",
                category = ExerciseCategory.STRENGTH,
            ),
        )
        val epochDay = 20_500L

        repeat(2) { round ->
            val sets = round + 1
            database.checkInDao().upsert(
                CheckInEntity(
                    exerciseId = exerciseId,
                    dateEpochDay = epochDay,
                    dateStartMillis = epochDay * 86_400_000L,
                    completedSets = sets,
                    // 维护 v2 不变量：completed_sets == completed_sets_mask.countOneBits()
                    completedSetsMask = if (sets <= 0) 0 else (1 shl sets) - 1,
                    completedReps = 10,
                ),
            )
        }

        // 同一动作同一天两次 upsert → 仅 1 条（唯一约束 + v2 显式 upsert，命中即 UPDATE，非 REPLACE）。
        assertEquals(1, database.checkInDao().countOn(epochDay))
    }

    @Test
    fun habitLogUniqueConstraintMakesUpsertIdempotent() = runBlocking {
        val habitId = database.habitDao().upsert(
            HabitEntity(
                name = "喝水",
                frequency = HabitFrequency.DAILY,
            ),
        )
        val epochDay = 20_500L

        repeat(2) {
            database.habitLogDao().upsert(
                HabitLogEntity(
                    habitId = habitId,
                    dateEpochDay = epochDay,
                    dateStartMillis = epochDay * 86_400_000L,
                    isCompleted = true,
                ),
            )
        }

        assertEquals(1, database.habitLogDao().getAll().size)
    }
}
