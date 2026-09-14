package com.ironhabit.app.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ironhabit.app.di.ApplicationScope
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
 * 1. 弹出对应类型的本地通知；
 * 2. **自续期**：排「明天同一时刻」的下一次闹钟（规避 `setRepeating` 在 API 19+ 被批处理的漂移）；
 * 3. 用 `goAsync()` + `@ApplicationScope` 在后台完成续期，不阻塞主线程、不使用 `GlobalScope`。
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val typeName = intent.getStringExtra(EXTRA_TYPE) ?: return
        val type = typeOf(typeName)
        val hour = intent.getIntExtra(EXTRA_HOUR, INVALID_TIME)
        val minute = intent.getIntExtra(EXTRA_MINUTE, INVALID_TIME)

        notificationHelper.showReminder(type)

        if (hour in HOUR_RANGE && minute in MINUTE_RANGE) {
            val pendingResult = goAsync()
            applicationScope.launch {
                try {
                    reminderScheduler.scheduleNext(type, hour, minute)
                } finally {
                    pendingResult.finish()
                }
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

        private const val INVALID_TIME: Int = -1
        private val HOUR_RANGE: IntRange = 0..23
        private val MINUTE_RANGE: IntRange = 0..59

        /** 供 [ReminderSchedulerImpl] 构建闹钟 `PendingIntent` 的唯一入口。 */
        fun reminderIntent(context: Context, type: ReminderType, hour: Int, minute: Int): Intent =
            Intent(context, ReminderReceiver::class.java).apply {
                action = actionFor(type)
                putExtra(EXTRA_TYPE, type.name)
                putExtra(EXTRA_HOUR, hour)
                putExtra(EXTRA_MINUTE, minute)
            }

        private fun actionFor(type: ReminderType): String = when (type) {
            ReminderType.TRAINING -> ACTION_REMINDER_TRAINING
            ReminderType.HABIT -> ACTION_REMINDER_HABIT
        }

        private fun typeOf(name: String): ReminderType =
            ReminderType.entries.firstOrNull { it.name == name } ?: ReminderType.TRAINING
    }
}
