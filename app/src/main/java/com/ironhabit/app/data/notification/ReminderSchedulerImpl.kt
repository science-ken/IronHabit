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
        setAlarm(nextTriggerMillis(hour, minute), type, hour, minute, habitId = null)
    }

    override suspend fun scheduleNext(type: ReminderType, hour: Int, minute: Int) {
        // 触发后自续期：始终排「明天」同一时刻
        setAlarm(triggerAt(hour, minute, daysAhead = 1L), type, hour, minute, habitId = null)
    }

    override suspend fun scheduleHabit(habitId: Long, hour: Int, minute: Int) {
        setAlarm(
            nextTriggerMillis(hour, minute),
            ReminderType.HABIT,
            hour,
            minute,
            habitId = habitId,
        )
    }

    override suspend fun scheduleHabitNext(habitId: Long, hour: Int, minute: Int) {
        setAlarm(
            triggerAt(hour, minute, daysAhead = 1L),
            ReminderType.HABIT,
            hour,
            minute,
            habitId = habitId,
        )
    }

    override suspend fun cancel(type: ReminderType) {
        val pendingIntent = pendingIntent(type, hour = 0, minute = 0, habitId = null)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    override suspend fun cancelHabit(habitId: Long) {
        val pendingIntent =
            pendingIntent(ReminderType.HABIT, hour = 0, minute = 0, habitId = habitId)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    override suspend fun cancelAll() {
        cancel(ReminderType.TRAINING)
        cancel(ReminderType.HABIT)
        for (habit in habitRepository.allHabits()) {
            cancelHabit(habit.id)
        }
    }

    override suspend fun rescheduleAll() {
        // 训练提醒：来自全局设置
        val settings = settingsRepository.settings().first()
        if (settings.reminderEnabled) {
            schedule(ReminderType.TRAINING, settings.reminderHour, settings.reminderMinute)
        } else {
            cancel(ReminderType.TRAINING)
        }

        // 遗留的「习惯共用一个槽」闹钟：无论现在有没有习惯要提醒，一律撤掉。
        // 2.0.9 起不再往这个槽排，但**升级前**排进去的会一直留着，
        // 不主动撤的话老用户会每天收到一条不指认任何习惯的提醒。
        cancel(ReminderType.HABIT)

        // 每个习惯各占一槽。遍历**全部**习惯（含已停用）而不是只遍历该排的，
        // 这样"上次排了、这次不该排"的槽会被就地撤掉 —— 不需要额外存"上次排了哪些"。
        for (habit in habitRepository.allHabits()) {
            val hour = habit.reminderHour
            val minute = habit.reminderMinute
            if (habit.isActive && habit.reminderEnabled && hour != null && minute != null) {
                scheduleHabit(habit.id, hour, minute)
            } else {
                cancelHabit(habit.id)
            }
        }
    }

    /** 「今天该时刻还没过就排今天，否则顺延到明天」。 */
    private fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val today = triggerAt(hour, minute, daysAhead = 0L)
        return if (today > nowMillis()) today else triggerAt(hour, minute, daysAhead = 1L)
    }

    private fun setAlarm(
        triggerAtMillis: Long,
        type: ReminderType,
        hour: Int,
        minute: Int,
        habitId: Long?,
    ) {
        val pendingIntent = pendingIntent(type, hour, minute, habitId)
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

    private fun pendingIntent(
        type: ReminderType,
        hour: Int,
        minute: Int,
        habitId: Long?,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ReminderType.slotFor(type, habitId),
            ReminderReceiver.reminderIntent(context, type, hour, minute, habitId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val MILLIS_PER_HOUR: Long = 3_600_000L
        const val MILLIS_PER_MINUTE: Long = 60_000L
    }
}
