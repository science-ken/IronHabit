package com.ironhabit.app.data.repository

import com.ironhabit.app.data.local.dao.CheckInDao
import com.ironhabit.app.data.local.dto.ExerciseProgressRaw
import com.ironhabit.app.data.mapper.CheckInMapper
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.ExerciseProgress
import com.ironhabit.app.domain.model.MAX_SETS
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.util.DateUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * [CheckInRepository] 的 data 层实现。
 */
@Singleton
class CheckInRepositoryImpl @Inject constructor(
    private val checkInDao: CheckInDao,
    private val clock: Clock,
    private val timeZone: TimeZone,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CheckInRepository {

    override fun observeByDate(epochDay: Long): Flow<List<CheckIn>> =
        checkInDao.observeByDate(epochDay)
            .map { entities -> entities.map(CheckInMapper::toDomain) }
            .flowOn(ioDispatcher)

    override fun observeByExercise(exerciseId: Long): Flow<List<CheckIn>> =
        checkInDao.observeByExercise(exerciseId)
            .map { entities -> entities.map(CheckInMapper::toDomain) }
            .flowOn(ioDispatcher)

    override suspend fun getForExerciseOnDate(exerciseId: Long, epochDay: Long): CheckIn? =
        checkInDao.getForExerciseOnDate(exerciseId, epochDay)?.let(CheckInMapper::toDomain)

    /**
     * 幂等 upsert（**禁用 `REPLACE`**）。
     *
     * 走 DAO 的显式 upsert：命中已有行 → `UPDATE`（保留原 `id`），未命中 → `INSERT`。
     * 与 `week_plans` 同模式，避免 `REPLACE` 的 `DELETE` + `INSERT` 重建行 id。
     */
    override suspend fun upsert(checkIn: CheckIn): Long =
        checkInDao.upsert(CheckInMapper.toEntity(checkIn))

    override suspend fun delete(exerciseId: Long, epochDay: Long) {
        checkInDao.deleteOn(exerciseId, epochDay)
    }

    /**
     * 活跃日（streak 输入）。
     *
     * **统计加固**：过滤掉 `dateEpochDay > 今天` 的记录 —— 防御历史脏数据（例如旧版本
     * 「日期游标可写到未来日」留下的未来打卡）把 streak 的 head 顶成未来日、导致 current 归零。
     */
    override fun observeActiveDaysSince(epochDay: Long): Flow<List<Long>> =
        checkInDao.observeActiveDaysSince(epochDay)
            .map { days ->
                val today = DateUtils.todayEpochDay(clock, timeZone)
                days.filter { it <= today }
            }
            .flowOn(ioDispatcher)

    override fun observeBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<CheckIn>> =
        checkInDao.observeBetween(startEpochDay, endEpochDay)
            .map { entities -> entities.map(CheckInMapper::toDomain) }
            .flowOn(ioDispatcher)

    /**
     * 逐组勾选（幂等切换第 [setIndex] 组）。
     *
     * 越界（含负数）**静默忽略** —— 绝不用 `require()` 抛异常（真机抛异常即崩）。
     *
     * 整个「读 → `xor` → 写」**下沉到 DAO 的单个事务**（`CheckInDao.toggleSetBit`）：
     * 修复前这里自己分三条 DAO 调用做读改写，两次快速连点会读到同一个旧 mask 而**静默丢更新**，
     * 种子插入还会撞 `UNIQUE(exercise_id, date_epoch_day)` 的 `ABORT` 冲突而抛异常。
     * 仓库层只负责「构造种子行模板」（`dateStartMillis` 需要 [clock] / [timeZone]，DAO 拿不到），
     * 以及越界早退。
     */
    override suspend fun toggleSet(exerciseId: Long, epochDay: Long, setIndex: Int) {
        // 越界（含负数）静默忽略 —— 绝不用 require() 抛异常（真机抛异常即崩）。
        if (setIndex !in 0 until MAX_SETS) return

        val nowMillis = clock.now().toEpochMilliseconds()
        // 行不存在时由 DAO 插入的空记录模板：mask = 0，保证不变量从零开始。
        val seed = CheckIn(
            exerciseId = exerciseId,
            dateEpochDay = epochDay,
            dateStartMillis = startOfDayMillis(epochDay),
            completedSetsMask = 0,
            isQuick = false,
            loggedAtMillis = nowMillis,
            createdAt = nowMillis,
        )
        // 读改写 + 种子插入在同一事务内完成；mask 与派生列由 DAO 同写。
        checkInDao.toggleSetBit(
            exerciseId = exerciseId,
            epochDay = epochDay,
            setIndex = setIndex,
            seed = CheckInMapper.toEntity(seed),
        )
    }

    override suspend fun setRpe(exerciseId: Long, epochDay: Long, rpe: Int?) {
        checkInDao.updateRpe(exerciseId, epochDay, rpe?.coerceIn(MIN_RPE, MAX_RPE))
    }

    /**
     * 每个动作"最近一次"的完成情况（**只读**，供本地规则引擎做渐进超负荷）。
     *
     * 目标组数缺失时（打卡记录未关联计划 `plan_id`）回落到 [FALLBACK_TARGET_SETS]，
     * 保证下游的"是否做满"判定**永远有判据**、不产生除零或 NaN。
     */
    override fun latestProgressPerExercise(): Flow<List<ExerciseProgress>> =
        checkInDao.observeLatestPerExercise()
            .map { rows -> rows.map(ExerciseProgressRaw::toDomain) }
            .flowOn(ioDispatcher)

    /** 把 epochDay 换算为当天本地 00:00 的 UTC 毫秒时间戳（架构 §7.3）。 */
    private fun startOfDayMillis(epochDay: Long): Long =
        LocalDate.fromEpochDays(epochDay.toInt()).atStartOfDayIn(timeZone).toEpochMilliseconds()

    private companion object {
        const val MIN_RPE = 1
        const val MAX_RPE = 10
    }
}

/** 聚合投影 → 领域模型（目标组数缺失时回落常量）。 */
private fun ExerciseProgressRaw.toDomain(): ExerciseProgress = ExerciseProgress(
    exerciseId = exerciseId,
    lastSetsCompleted = lastSetsCompleted,
    lastTargetSets = lastTargetSets ?: FALLBACK_TARGET_SETS,
    lastRpe = lastRpe,
    lastWeightKg = lastWeightKg,
)

/** 目标组数兜底：打卡记录未关联计划（`plan_id = NULL`）时的判据（文件级常量，便于单测引用）。 */
private const val FALLBACK_TARGET_SETS = 3
