package com.ironhabit.app.domain.repository

import com.ironhabit.app.domain.model.CheckIn
import kotlinx.coroutines.flow.Flow

/**
 * 训练打卡仓库接口。
 */
interface CheckInRepository {

    /** 观察某天的全部打卡记录（按创建时间倒序）。 */
    fun observeByDate(epochDay: Long): Flow<List<CheckIn>>

    /** 观察某动作的全部打卡记录（按日期倒序）。 */
    fun observeByExercise(exerciseId: Long): Flow<List<CheckIn>>

    /** 查询某动作某天的打卡记录，不存在返回 `null`。 */
    suspend fun getForExerciseOnDate(exerciseId: Long, epochDay: Long): CheckIn?

    /** 新增或更新打卡记录，返回行 id（依赖唯一约束实现幂等）。 */
    suspend fun upsert(checkIn: CheckIn): Long

    /** 撤销某动作某天的打卡。 */
    suspend fun delete(exerciseId: Long, epochDay: Long)

    /** 观察自 `epochDay`（含）起所有有打卡的日期，降序（streak 输入）。 */
    fun observeActiveDaysSince(epochDay: Long): Flow<List<Long>>

    /** 观察 `[startEpochDay, endEpochDay]` 闭区间内的全部打卡记录，按日期升序（历史/统计用）。 */
    fun observeBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<CheckIn>>

    /**
     * 勾选 / 取消第 [setIndex] 组（0-based）。
     *
     * 维护 `completedSetsMask`（唯一真源）与派生列 `completedSets` 的同步写入；
     * 行不存在时先建一条空记录。越界（`>= MAX_SETS`）静默忽略，绝不抛异常。
     */
    suspend fun toggleSet(exerciseId: Long, epochDay: Long, setIndex: Int)

    /** 写入 / 清除某动作某天的 RPE（`1..10`，空 = 清除）。 */
    suspend fun setRpe(exerciseId: Long, epochDay: Long, rpe: Int?)
}
