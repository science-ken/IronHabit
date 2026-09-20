package com.ironhabit.app.domain.repository

import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitLog
import kotlinx.coroutines.flow.Flow

/**
 * 习惯仓库接口（定义 + 日志 + 今日状态）。
 */
interface HabitRepository {

    /** 观察全部启用习惯。 */
    fun observeActiveHabits(): Flow<List<Habit>>

    /**
     * 一次性快照：**含已停用 / 已软删**的全部习惯。
     *
     * 只给提醒重排用 —— 重排必须能把"曾经排过、现在不该再响"的那些槽撤掉，
     * 只看启用中的习惯就找不到它们了。界面不要用它（会把已删习惯渲染出来）。
     */
    suspend fun allHabits(): List<Habit>

    /** 按 id 取单个习惯（提醒通知里要写习惯名，所以广播侧要用）。不存在返回 `null`。 */
    suspend fun getHabit(habitId: Long): Habit?

    /** 观察日期区间内的习惯日志。 */
    fun observeLogsBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<HabitLog>>

    /** 查询某习惯某天的日志，不存在返回 `null`。 */
    suspend fun getLogOnDate(habitId: Long, epochDay: Long): HabitLog?

    /** 新增或更新习惯定义，返回行 id。 */
    suspend fun upsertHabit(habit: Habit): Long

    /**
     * 设置某习惯某天的完成状态（幂等 upsert）。
     *
     * @param done 完成 = `true`，取消 = `false`
     * @param note 备注，可空
     */
    suspend fun setLog(habitId: Long, epochDay: Long, done: Boolean, note: String? = null)

    /**
     * 观察某习惯全部**已完成**的日期（`is_completed = 1`），降序（连续天数计算输入）。
     *
     * 注意：`setLog(done = false)` 是幂等 upsert，会把 `is_completed` 置 0 **而非删除行**，
     * 因此"表中有记录"≠"已完成"。查询侧的 `AND is_completed = 1` 是必需过滤，不可移除。
     */
    fun observeActiveDays(habitId: Long): Flow<List<Long>>

    /** 软删除习惯（`isActive = false`），保留其历史日志。 */
    suspend fun deleteHabit(habitId: Long)

    /** 习惯排序：按 [orderedIds] 的顺序依次写 `sortOrder`（`0, 1, 2, …`）。 */
    suspend fun reorderHabits(orderedIds: List<Long>)
}
