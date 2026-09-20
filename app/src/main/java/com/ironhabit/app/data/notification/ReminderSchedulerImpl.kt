package com.ironhabit.app.data.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.ReminderScheduler
import com.ironhabit.app.domain.repository.ReminderType
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.util.DateUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * [ReminderScheduler] 的 data 层实现：用 **AlarmManager 精确闹钟**做本地提醒。
 *
 * - API 31+ 且具备 `SCHEDULE_EXACT_ALARM` 权限 → `setExactAndAllowWhileIdle`（精确到点）；
 * - 否则**自动降级** `setAndAllowWhileIdle`（不精确但可用），设置页可读系统权限状态并提示；
 * - 触发后由 [ReminderReceiver] 调用 [scheduleNext] 自续期「明天同一时刻」。
 *
 * 由 `NotificationModule` 通过 `@Binds` 绑定到接口。
 *
 * ⚠️ **不加 `@Singleton`**（`NotificationModule.bindReminderScheduler` 那里也刻意没加）：
 * 本类注入了 `TimeZone`，一旦被单例化，时区就被冻在进程启动那一刻，
 * 设备跨时区后"每天 07:30"仍按旧时区排。不作用域 → 每次广播 / 每个 ViewModel 新建实例、重新求值。
 * 本类**没有任何可变状态**（只有注入依赖），多实例不会重复登记闹钟：
 * 闹钟由 `PendingIntent` 的 requestCode 去重。
 */
class ReminderSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager,
    private val settingsRepository: SettingsRepository,
    private val habitRepository: HabitRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : ReminderScheduler {

    override suspend fun schedule(type: ReminderType, hour: Int, minute: Int) {
        var triggerAtMillis = triggerAt(hour, minute, daysAhead = 0L)
        if (triggerAtMillis <= nowMillis()) {
            // 今天该时刻已过 → 顺延到明天
            triggerAtMillis = triggerAt(hour, minute, daysAhead = 1L)
        }
        setAlarm(triggerAtMillis, type, hour, minute)
    }

    override suspend fun scheduleNext(type: ReminderType, hour: Int, minute: Int) {
        // 触发后自续期：始终排「明天」同一时刻
        setAlarm(triggerAt(hour, minute, daysAhead = 1L), type, hour, minute)
    }

    override suspend fun cancel(type: ReminderType) {
        val pendingIntent = pendingIntent(type, hour = 0, minute = 0)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    override suspend fun cancelAll() {
        cancel(ReminderType.TRAINING)
        cancel(ReminderType.HABIT)
    }

    override suspend fun rescheduleAll() {
        // 训练提醒：来自全局设置
        val settings = settingsRepository.settings().first()
        if (settings.reminderEnabled) {
            schedule(ReminderType.TRAINING, settings.reminderHour, settings.reminderMinute)
        } else {
            cancel(ReminderType.TRAINING)
        }

        // 习惯提醒：取最早的一条（单 requestCode 约束下只保留一个习惯提醒槽）
        val earliestHabitTime = earliestHabitReminder()
        if (earliestHabitTime != null) {
            schedule(ReminderType.HABIT, earliestHabitTime.first, earliestHabitTime.second)
        } else {
            cancel(ReminderType.HABIT)
        }
    }

    /** 在所有「启用且设定了时间」的习惯里，取一天中最早的那一个（hour, minute）。 */
    private suspend fun earliestHabitReminder(): Pair<Int, Int>? {
        val habits = habitRepository.observeActiveHabits().first()
        var best: HabitReminder? = null
        for (habit in habits) {
            val hour = habit.reminderHour ?: continue
            val minute = habit.reminderMinute ?: continue
            if (!habit.reminderEnabled) continue
            val minutesOfDay = hour * MINUTES_PER_HOUR + minute
            if (best == null || minutesOfDay < best!!.minutesOfDay) {
                best = HabitReminder(minutesOfDay, hour, minute)
            }
        }
        return best?.let { it.hour to it.minute }
    }

    private fun setAlarm(triggerAtMillis: Long, type: ReminderType, hour: Int, minute: Int) {
        val pendingIntent = pendingIntent(type, hour, minute)
        if (canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            // 降级：不精确闹钟（可能被系统批处理，时间略有偏差）
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    /** API 31+ 需系统授予「精确闹钟」权限；低版本始终可用。 */
    private fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    /** 目标时刻 = `today + daysAhead` 当天 00:00 + hour:minute（本地时区）。 */
    private fun triggerAt(hour: Int, minute: Int, daysAhead: Long): Long {
        val day = DateUtils.todayEpochDay(clock, timeZone) + daysAhead
        return DateUtils.startOfDayMillis(day, timeZone) +
            hour * MILLIS_PER_HOUR +
            minute * MILLIS_PER_MINUTE
    }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    private fun pendingIntent(type: ReminderType, hour: Int, minute: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            type.requestCode,
            ReminderReceiver.reminderIntent(context, type, hour, minute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 习惯提醒时间候选。 */
    private data class HabitReminder(
        val minutesOfDay: Int,
        val hour: Int,
        val minute: Int,
    )

    private companion object {
        const val MINUTES_PER_HOUR: Int = 60
        const val MILLIS_PER_HOUR: Long = 3_600_000L
        const val MILLIS_PER_MINUTE: Long = 60_000L
    }
}
