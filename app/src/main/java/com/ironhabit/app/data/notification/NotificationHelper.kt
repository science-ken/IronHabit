package com.ironhabit.app.data.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ironhabit.app.MainActivity
import com.ironhabit.app.R
import com.ironhabit.app.domain.repository.ReminderType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知构建与发送（架构 §4.3）。
 *
 * 到点触发时由 [ReminderReceiver] 调用 [showReminder]：
 * - 先校验通知可用性（系统开关 + API 33+ `POST_NOTIFICATIONS` 运行时权限），不可用则静默返回；
 * - 点击通知 → 打开 [MainActivity]。
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManager: NotificationManagerCompat,
) {

    /** 通知是否可用（系统级开关，供设置页展示提示）。 */
    fun areNotificationsEnabled(): Boolean = notificationManager.areNotificationsEnabled()

    /** 按提醒类型弹出本地通知。 */
    fun showReminder(type: ReminderType) {
        if (!canPostNotifications()) return

        val titleRes = when (type) {
            ReminderType.TRAINING -> R.string.notif_training_title
            ReminderType.HABIT -> R.string.notif_habit_title
        }
        val textRes = when (type) {
            ReminderType.TRAINING -> R.string.notif_training_text
            ReminderType.HABIT -> R.string.notif_habit_text
        }

        val notification = NotificationCompat.Builder(context, NotificationChannels.channelIdFor(type))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(textRes))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVibrate(VIBRATION_PATTERN)
            .setContentIntent(contentIntent(type))
            .build()

        notificationManager.notify(notificationIdFor(type), notification)
    }

    /** 通知可发：系统开关开启，且 API 33+ 已授予 `POST_NOTIFICATIONS`。 */
    private fun canPostNotifications(): Boolean {
        if (!notificationManager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    /** 点击通知打开主界面。 */
    private fun contentIntent(type: ReminderType): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationIdFor(type),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationIdFor(type: ReminderType): Int = when (type) {
        ReminderType.TRAINING -> NOTIFICATION_ID_TRAINING
        ReminderType.HABIT -> NOTIFICATION_ID_HABIT
    }

    private companion object {
        const val NOTIFICATION_ID_TRAINING: Int = 1001
        const val NOTIFICATION_ID_HABIT: Int = 1002

        /** 「延迟 0、震 250、停 200、再震 250」（毫秒）。 */
        val VIBRATION_PATTERN: LongArray = longArrayOf(0L, 250L, 200L, 250L)
    }
}
