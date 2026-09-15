package com.ironhabit.app.data.repository

import com.ironhabit.app.data.local.dao.CheckInDao
import com.ironhabit.app.data.local.dto.ExerciseProgressRaw
import com.ironhabit.app.data.local.entity.CheckInEntity
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.usecase.DetailedCheckInUseCase
import com.ironhabit.app.domain.usecase.QuickCheckInUseCase
import com.ironhabit.app.domain.util.DateUtils
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `check_ins` 显式 upsert 行为验证 + **RPE 保留/覆盖**实测。
 *
 * 用内存版 [CheckInDao] 承接真实的生产逻辑：`CheckInRepositoryImpl` 与
 * [QuickCheckInUseCase] / [DetailedCheckInUseCase] 都是**未改动的生产类**，
 * 唯一被替换的是 Room 生成的 DAO（换成内存 map，但继承接口里的默认 `upsert` 逻辑）。
 *
 * Round-3：`upsert` 命中已有行且入参 `rpe == null` 时沿用旧 rpe（修复「补录/再次打卡
 * 把用户已录强度静默抹成 NULL」）；入参显式给出 rpe 时以入参为准。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckInRpeUpsertTest {

    private val dao = FakeCheckInDao()
    private val clock = Clock.System
    private val timeZone = TimeZone.UTC
    private val repository = CheckInRepositoryImpl(dao, clock, timeZone, UnconfinedTestDispatcher())
    private val exerciseRepository = mockk<ExerciseRepository>(relaxed = true)

    private val today = DateUtils.todayEpochDay(Clock.System, TimeZone.UTC)
    private val plan = WeekPlan(id = 1L, exerciseId = 1L, dayOfWeek = 1, targetSets = 3, targetReps = 10)

    // ---- 显式 upsert 本意：不应重建主键 id ----

    @Test
    fun explicitUpsertPreservesRowIdOnHit() = runTest {
        val firstId = repository.upsert(
            CheckIn(exerciseId = 1L, dateEpochDay = today, dateStartMillis = 0L),
        )
        val secondId = repository.upsert(
            CheckIn(exerciseId = 1L, dateEpochDay = today, dateStartMillis = 0L, completedReps = 20),
        )

        assertEquals("命中已存在行必须保留原 id（禁用 REPLACE 的核心目的）", firstId, secondId)
        assertEquals(1, dao.countOn(today))
    }

    // ---- 安全路径：逐组勾选只改 mask，不碰 rpe ----

    @Test
    fun toggleSetKeepsExistingRpe() = runTest {
        repository.upsert(CheckIn(exerciseId = 1L, dateEpochDay = today, dateStartMillis = 0L))
        repository.setRpe(1L, today, 8)
        assertEquals(8, dao.getForExerciseOnDate(1L, today)?.rpe)

        repository.toggleSet(1L, today, 2) // 「再点某一组」

        assertEquals("toggledSet 只写 mask，RPE 应保留", 8, dao.getForExerciseOnDate(1L, today)?.rpe)
    }

    // ---- 写入口径统一：一键打卡落到调用方传入的所选日 ----

    @Test
    fun quickCheckInHonorsCallerSuppliedEpochDay() = runTest {
        val selectedDay = today + 3L
        QuickCheckInUseCase(repository, exerciseRepository, clock, timeZone)(plan, selectedDay)

        assertNotNull(
            "口径统一：一键打卡落在调用方传入的所选日，而非用例内部自算的「今天」",
            dao.getForExerciseOnDate(1L, selectedDay),
        )
        assertNull(
            "所选日 != 今天时，今天不应被写入",
            dao.getForExerciseOnDate(1L, today),
        )
    }

    // ---- RPE 保留：整行 upsert 未显式给 rpe 时不得清掉已录值 ----

    /**
     * ⚠️ **characterization 断言反转（Round-3）。**
     *
     * 触发路径：先「一键打卡」→ 录 RPE=8 → 打开补录弹层「改完成组数」提交
     * （`TodayScreen` → `TodayViewModel.onDetailedCheckIn` → `DetailedCheckInUseCase` → `CheckInRepositoryImpl.upsert`
     * → `CheckInDao.upsert`）。补录构造的 `CheckIn` 不携带 rpe（= null）。
     *
     * - **修复前**（本测试最初记录的是**缺陷行为**）：整行覆盖把 `rpe` 抹成 NULL。
     * - **修复后**（`CheckInDao.upsert` 命中已有行且入参 rpe==null → `entity.copy(id = existing.id, rpe = existing.rpe)`）：
     *   **保留旧 rpe = 8**。断言已随之反转为「新的正确行为」。
     */
    @Test
    fun detailedReCheckAfterRpeKeepsExistingRpe() = runTest {
        // 1) 一键打卡 → 2) 录 RPE
        QuickCheckInUseCase(repository, exerciseRepository, clock, timeZone)(plan, today)
        repository.setRpe(1L, today, 8)
        assertEquals(8, dao.getForExerciseOnDate(1L, today)?.rpe)

        // 3) 补录弹层「改完成组数」提交（不带 rpe 的整行 upsert）
        DetailedCheckInUseCase(repository, exerciseRepository, clock, timeZone)(
            exerciseId = 1L,
            planId = null,
            epochDay = today,
            sets = 5,
            reps = 10,
        )

        assertEquals(
            "补录不带 rpe 时，应保留用户已录入的强度（修复：事务内沿用旧 rpe，不再抹 NULL）",
            8,
            dao.getForExerciseOnDate(1L, today)?.rpe,
        )
    }

    /**
     * ⚠️ **characterization 断言反转（Round-3）。**
     *
     * 同一动作同一天**再次一键打卡**（整行 upsert）。
     * - **修复前**（缺陷行为）：清空已录 RPE。
     * - **修复后**：保留已录 RPE = 8。
     */
    @Test
    fun quickCheckInAgainAfterRpeKeepsExistingRpe() = runTest {
        QuickCheckInUseCase(repository, exerciseRepository, clock, timeZone)(plan, today)
        repository.setRpe(1L, today, 8)

        // 再次一键打卡（同一动作同一天）→ 整行 upsert 覆盖
        QuickCheckInUseCase(repository, exerciseRepository, clock, timeZone)(plan, today)

        assertEquals(
            "再次一键打卡不得清空已录 RPE（修复：入参 rpe==null 时沿用旧值）",
            8,
            dao.getForExerciseOnDate(1L, today)?.rpe,
        )
    }

    /**
     * **反向守卫（防「永远保留旧值」）**：入参**显式**给出 rpe 时，必须覆盖旧值。
     *
     * 若只测「保留」而不测「覆盖」，修复可能退化成「rpe 一旦写入就永不更新」的另一种缺陷。
     */
    @Test
    fun explicitRpeInputOverwritesExistingRpe() = runTest {
        repository.upsert(
            CheckIn(exerciseId = 1L, dateEpochDay = today, dateStartMillis = 0L, rpe = 5),
        )
        assertEquals(5, dao.getForExerciseOnDate(1L, today)?.rpe)

        repository.upsert(
            CheckIn(exerciseId = 1L, dateEpochDay = today, dateStartMillis = 0L, rpe = 9),
        )

        assertEquals(
            "入参显式给出 rpe 时必须覆盖旧值（防「永远保留旧值」）",
            9,
            dao.getForExerciseOnDate(1L, today)?.rpe,
        )
    }

    /** 入参 rpe==null 且旧行 rpe 也为 null → 保持 null（不误写 0 或其它默认值）。 */
    @Test
    fun nullRpeInputWithNullExistingStaysNull() = runTest {
        repository.upsert(CheckIn(exerciseId = 1L, dateEpochDay = today, dateStartMillis = 0L))
        repository.upsert(CheckIn(exerciseId = 1L, dateEpochDay = today, dateStartMillis = 0L, completedReps = 7))

        assertNull(
            "旧行 rpe 本就为 null、入参也为 null → 应保持 null",
            dao.getForExerciseOnDate(1L, today)?.rpe,
        )
    }
}

/**
 * 最小内存版 [CheckInDao]：用 `(exerciseId,dateEpochDay)` 唯一槽位模拟真实表。
 *
 * 关键：**不重写 `upsert`**，从而复用接口里生产环境使用的那份默认实现。
 * 其余方法只实现本测试触达的子集。
 */
private class FakeCheckInDao : CheckInDao {

    private val rows = LinkedHashMap<Long, CheckInEntity>()
    private var nextId = 1L

    private fun find(exerciseId: Long, epochDay: Long): CheckInEntity? =
        rows.values.firstOrNull { it.exerciseId == exerciseId && it.dateEpochDay == epochDay }

    override fun observeByDate(epochDay: Long): Flow<List<CheckInEntity>> =
        flowOf(rows.values.filter { it.dateEpochDay == epochDay })

    override fun observeByExercise(exerciseId: Long): Flow<List<CheckInEntity>> =
        flowOf(rows.values.filter { it.exerciseId == exerciseId })

    override suspend fun getForExerciseOnDate(exerciseId: Long, epochDay: Long): CheckInEntity? =
        find(exerciseId, epochDay)

    override fun observeActiveDays(): Flow<List<Long>> = flowOf(emptyList())

    override fun observeActiveDaysSince(sinceEpochDay: Long): Flow<List<Long>> =
        flowOf(rows.values.map { it.dateEpochDay }.distinct().sortedDescending())

    override fun observeBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<CheckInEntity>> =
        flowOf(
            rows.values
                .filter { it.dateEpochDay in startEpochDay..endEpochDay }
                .sortedBy { it.dateEpochDay },
        )

    override suspend fun insert(entity: CheckInEntity): Long {
        val id = if (entity.id != 0L) entity.id else nextId++
        rows[id] = entity.copy(id = id)
        return id
    }

    override suspend fun insertAll(entities: List<CheckInEntity>): List<Long> =
        entities.map { insert(it) }

    override suspend fun update(entity: CheckInEntity) {
        rows[entity.id] = entity
    }

    override suspend fun deleteOn(exerciseId: Long, epochDay: Long) {
        rows.values.removeAll { it.exerciseId == exerciseId && it.dateEpochDay == epochDay }
    }

    override suspend fun updateSetMask(exerciseId: Long, epochDay: Long, mask: Int, completedSets: Int) {
        val row = find(exerciseId, epochDay) ?: return
        rows[row.id] = row.copy(completedSetsMask = mask, completedSets = completedSets)
    }

    override suspend fun updateRpe(exerciseId: Long, epochDay: Long, rpe: Int?) {
        val row = find(exerciseId, epochDay) ?: return
        rows[row.id] = row.copy(rpe = rpe)
    }

    override suspend fun countOn(epochDay: Long): Int =
        rows.values.count { it.dateEpochDay == epochDay }

    override suspend fun getAll(): List<CheckInEntity> = rows.values.sortedBy { it.dateEpochDay }

    override suspend fun clearAll() {
        rows.clear()
    }

    // 本测试只验证 upsert / RPE 行为，不触达「最近一次完成情况」聚合查询 → 给空流即可。
    override fun observeLatestPerExercise(): Flow<List<ExerciseProgressRaw>> = flowOf(emptyList())
}
