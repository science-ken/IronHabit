package com.ironhabit.app.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ironhabit.app.di.ApplicationScope
import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.ReminderScheduler
import com.ironhabit.app.domain.repository.ReminderType
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 提醒广播接收器（架构 §4.3）。
 *
 * 由 `AlarmManager` 的精确闹钟触发：
 * 1. 习惯提醒要先查一次库拿**习惯名**（通知得指认是哪一件）；
 * 2. 弹出本地通知；
 * 3. **自续期**：排「明天同一时刻」的下一次闹钟（规避 `setRepeating` 在 API 19+ 被批处理的漂移）；
 * 4. 用 `goAsync()` + `@ApplicationScope` 在后台完成查库和续期，不阻塞主线程、不使用 `GlobalScope`。
 *
 * 两条**不续排**的出口，都是为了让闹钟自己死掉而不是变成永久孤儿：
 * - 习惯已被删除 → 不发通知、不续排，撤掉它那个槽；
 * - 升级前遗留的通用习惯闹钟（不带 habitId）→ 最后响一次，然后撤掉遗留的 2 号槽。
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    /** 只为拿习惯名（通知要指认是哪个习惯），不做任何调度。 */
    @Inject
    lateinit var habitRepository: HabitRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val typeName = intent.getStringExtra(EXTRA_TYPE) ?: return
        val type = typeOf(typeName)
        val hour = intent.getIntExtra(EXTRA_HOUR, INVALID_TIME)
        val minute = intent.getIntExtra(EXTRA_MINUTE, INVALID_TIME)
        // 没带这个 extra 的是**升级前**排下的通用习惯闹钟（那时全部习惯共用一个槽）。
        val habitId: Long? =
            if (intent.hasExtra(EXTRA_HABIT_ID)) intent.getLongExtra(EXTRA_HABIT_ID, 0L) else null

        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                if (type == ReminderType.HABIT && habitId == null) {
                    // 遗留槽：让它**最后响一次**，然后撤掉 2 号槽、不再续排。
                    // 若照常续排，这条通用提醒会和"每习惯一条"长期并存，永远清不干净。
                    notificationHelper.showReminder(type, habit = null)
                    reminderScheduler.cancel(ReminderType.HABIT)
                    return@launch
                }

                // 习惯提醒要写习惯名，所以要查一次库；训练提醒不需要。
                val habit = habitId?.let { id -> habitRepository.getHabit(id) }

                if (type == ReminderType.HABIT && habit == null) {
                    // 习惯在这条闹钟触发前已被删除 → 不发通知，也不再续排
                    // （续排下去就是一个永远没人撤的孤儿闹钟）。
                    habitId?.let { id -> reminderScheduler.cancelHabit(id) }
                    return@launch
                }
                notificationHelper.showReminder(type, habit)

                if (hour in HOUR_RANGE && minute in MINUTE_RANGE) {
                    if (habit == null) {
                        reminderScheduler.scheduleNext(type, hour, minute)
                    } else {
                        reminderScheduler.scheduleHabitNext(habit.id, hour, minute)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /** 训练提醒 Intent action。 */
        const val ACTION_REMINDER_TRAINING: String = "com.ironhabit.app.action.REMINDER_TRAINING"

        /** 习惯提醒 Intent action。 */
        const val ACTION_REMINDER_HABIT: String = "com.ironhabit.app.action.REMINDER_HABIT"

        const val EXTRA_TYPE: String = "extra_reminder_type"
        const val EXTRA_HOUR: String = "extra_reminder_hour"
        const val EXTRA_MINUTE: String = "extra_reminder_minute"

        /** 这条闹钟属于哪个习惯（只有 HABIT 才带）。 */
        const val EXTRA_HABIT_ID: String = "extra_reminder_habit_id"

        private const val INVALID_TIME: Int = -1
        private val HOUR_RANGE: IntRange = 0..23
        private val MINUTE_RANGE: IntRange = 0..59

        /**
         * 供 [ReminderSchedulerImpl] 构建闹钟 `PendingIntent` 的唯一入口。
         *
         * ⚠️ `habitId` 只是**给通知内容用**的；闹钟之间的区分靠 requestCode
         * （`PendingIntent` 比较 Intent 时不看 extras），所以这里加 extra 不代表分槽。
         */
        fun reminderIntent(
            context: Context,
            type: ReminderType,
            hour: Int,
            minute: Int,
            habitId: Long?,
        ): Intent =
            Intent(context, ReminderReceiver::class.java).apply {
                action = actionFor(type)
                putExtra(EXTRA_TYPE, type.name)
                putExtra(EXTRA_HOUR, hour)
                putExtra(EXTRA_MINUTE, minute)
                if (habitId != null) putExtra(EXTRA_HABIT_ID, habitId)
            }

        private fun actionFor(type: ReminderType): String = when (type) {
            ReminderType.TRAINING -> ACTION_REMINDER_TRAINING
            ReminderType.HABIT -> ACTION_REMINDER_HABIT
        }

        private fun typeOf(name: String): ReminderType =
            ReminderType.entries.firstOrNull { it.name == name } ?: ReminderType.TRAINING
    }
}
