package com.ironhabit.app.data.repository

import com.ironhabit.app.data.local.dao.CheckInDao
import com.ironhabit.app.data.local.dto.ExerciseProgressRaw
import com.ironhabit.app.data.local.entity.CheckInEntity
import com.ironhabit.app.domain.usecase.CalculateStreakUseCase
import com.ironhabit.app.domain.util.DateUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脏数据场景：**历史遗留的「未来日」打卡**不得把 current streak 归零。
 *
 * 背景：旧版本日期游标可写到未来日，`check_ins` 里会留下未来日期活跃日；
 * 统计侧若原样喂给 [com.ironhabit.app.domain.util.StreakCalculator]，未来日会成为 head →
 * 触发「head 既非今天也非昨天 → current=0」。
 *
 * Round-3 采用**两层防御**，本文件分别针对两层取证：
 * 1. `CheckInRepositoryImpl.observeActiveDaysSince`（`:64-70`）在 data 层 `filter { it <= today }`；
 * 2. `StreakCalculator.calculate`（`:34-40`）在纯函数层再次过滤 + 空列表归零。
 *
 * 覆盖**两条 streak 路径**：训练（`activeDays`）与习惯（`habitLogs` 日期）——两者共用同一个计算器。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreakDirtyDataTest {

    private val clock = Clock.System
    private val timeZone = TimeZone.UTC
    private val today = DateUtils.todayEpochDay(clock, timeZone)
    private val tomorrow = today + 7

    // ---- 第 1 层防御：repository 过滤未来日（训练路径的 data 层） ----

    @Test
    fun repositoryFiltersFutureActiveDaysBeforeStreak() = runTest {
        val dao = FakeActiveDaysDao(listOf(tomorrow, today, today - 1))
        val repo = CheckInRepositoryImpl(dao, clock, timeZone, UnconfinedTestDispatcher())

        val days = repo.observeActiveDaysSince(0L).first()

        assertFalse("未来日必须被 repository 层过滤掉", days.contains(tomorrow))
        assertTrue("今天应保留", days.contains(today))
        assertTrue("昨天应保留", days.contains(today - 1))

        val streak = CalculateStreakUseCase(clock, timeZone)(days)
        assertEquals("脏数据未来日不得把训练 current 归零", 2, streak.current)
    }

    @Test
    fun repositoryFilterAloneProtectsEvenWithoutCalculatorGuard() = runTest {
        val dao = FakeActiveDaysDao(listOf(today, today - 1, today - 2, tomorrow))
        val repo = CheckInRepositoryImpl(dao, clock, timeZone, UnconfinedTestDispatcher())

        val days = repo.observeActiveDaysSince(0L).first()

        assertEquals("仅 data 层过滤后即为 [today,today-1,today-2]", 3, days.size)
    }

    // ---- 第 2 层防御：纯函数过滤（习惯路径直接喂原始日志日期；训练路径兜底） ----

    @Test
    fun habitStreakSurvivesFutureDirtyDay() = runTest {
        // 习惯路径：habitLogs 的日期集合（含未来脏数据）直接喂给同一个计算器
        val doneDays = listOf(tomorrow, today, today - 1)

        val streak = CalculateStreakUseCase(clock, timeZone)(doneDays)

        assertEquals("习惯 streak 同样不被未来日破坏", 2, streak.current)
        assertEquals("lastActive 落回今天", today, streak.lastActiveEpochDay)
    }

    @Test
    fun calculatorFiltersFutureEvenIfDataLayerDidNot() = runTest {
        // 绕过 repository，直接把含未来日的列表喂给计算器（模拟「第二层」独立生效）
        val streak = CalculateStreakUseCase(clock, timeZone)(listOf(tomorrow, today, today - 1))
        assertEquals("StreakCalculator 自身也必须过滤未来日", 2, streak.current)
    }

    // ---- 边界：只有未来脏数据 → 空 streak，且不得抛异常或记 best ----

    @Test
    fun onlyFutureDirtyDataYieldsZeroStreakButNoCrash() = runTest {
        val dao = FakeActiveDaysDao(listOf(tomorrow))
        val repo = CheckInRepositoryImpl(dao, clock, timeZone, UnconfinedTestDispatcher())

        val days = repo.observeActiveDaysSince(0L).first()
        assertEquals("过滤后无有效活跃日", 0, days.size)

        val streak = CalculateStreakUseCase(clock, timeZone)(days)
        assertEquals(0, streak.current)
        assertEquals("未来脏数据不得计入 best", 0, streak.best)
    }
}

/**
 * 仅实现 [CheckInRepositoryImpl.observeActiveDaysSince] 用到的
 * `observeActiveDaysSince`（返回**未过滤**的全量活跃日，以逼出 repository 层过滤逻辑）；
 * 其余方法本测试不触达，`error()` 兜底以防误用。
 */
private class FakeActiveDaysDao(private val activeDays: List<Long>) : CheckInDao {

    override fun observeActiveDaysSince(sinceEpochDay: Long): Flow<List<Long>> =
        flowOf(activeDays)

    override fun observeByDate(epochDay: Long): Flow<List<CheckInEntity>> = error("unused")
    override fun observeByExercise(exerciseId: Long): Flow<List<CheckInEntity>> = error("unused")
    override suspend fun getForExerciseOnDate(exerciseId: Long, epochDay: Long): CheckInEntity? =
        error("unused")

    override fun observeActiveDays(): Flow<List<Long>> = error("unused")
    override fun observeBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<CheckInEntity>> =
        error("unused")

    override suspend fun insert(entity: CheckInEntity): Long = error("unused")
    override suspend fun insertAll(entities: List<CheckInEntity>): List<Long> = error("unused")
    override suspend fun update(entity: CheckInEntity) = error("unused")
    override suspend fun deleteOn(exerciseId: Long, epochDay: Long) = error("unused")
    override suspend fun updateSetMask(
        exerciseId: Long,
        epochDay: Long,
        mask: Int,
        completedSets: Int,
    ) = error("unused")

    override suspend fun updateRpe(exerciseId: Long, epochDay: Long, rpe: Int?) = error("unused")
    override suspend fun countOn(epochDay: Long): Int = error("unused")
    override suspend fun getAll(): List<CheckInEntity> = error("unused")
    override suspend fun clearAll() = error("unused")
    override fun observeLatestPerExercise(): Flow<List<ExerciseProgressRaw>> = error("unused")
}
