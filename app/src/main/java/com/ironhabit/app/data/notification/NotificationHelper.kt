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
import com.ironhabit.app.domain.model.Habit
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

    /**
     * 弹出到点提醒。
     *
     * @param habit 习惯提醒传那个习惯 —— 通知标题直接写习惯名，用户才知道是**哪一件**该做；
     *              传 `null` 表示通用文案（训练提醒，以及升级前遗留的那条习惯闹钟）。
     */
    fun showReminder(type: ReminderType, habit: Habit? = null) {
        if (!canPostNotifications()) return

        val title = habit?.name ?: context.getString(titleResFor(type))
        val notificationId = notificationIdFor(type, habit)

        val notification = NotificationCompat.Builder(context, NotificationChannels.channelIdFor(type))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(textResFor(type)))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVibrate(VIBRATION_PATTERN)
            .setContentIntent(contentIntent(notificationId))
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private fun titleResFor(type: ReminderType): Int = when (type) {
        ReminderType.TRAINING -> R.string.notif_training_title
        ReminderType.HABIT -> R.string.notif_habit_title
    }

    private fun textResFor(type: ReminderType): Int = when (type) {
        ReminderType.TRAINING -> R.string.notif_training_text
        ReminderType.HABIT -> R.string.notif_habit_text
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
    private fun contentIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 通知 id 也必须按习惯分开：同一个 id 后发的通知会**顶掉**前一条，
     * 那样"三个习惯各提醒一次"在通知栏里就只剩最后一条。
     */
    private fun notificationIdFor(type: ReminderType, habit: Habit?): Int = when {
        type == ReminderType.TRAINING -> NOTIFICATION_ID_TRAINING
        habit == null -> NOTIFICATION_ID_HABIT_LEGACY
        else -> (NOTIFICATION_ID_HABIT_BASE + habit.id).toInt()
    }

    private companion object {
        const val NOTIFICATION_ID_TRAINING: Int = 1001

        /** 升级前那条"习惯共用一个槽"的遗留通知 id，仍要能用（老闹钟可能还在）。 */
        const val NOTIFICATION_ID_HABIT_LEGACY: Int = 1002

        /** 每习惯一条通知，id 从 2000 起按习惯 id 递增。 */
        const val NOTIFICATION_ID_HABIT_BASE: Long = 2_000L

        /** 「延迟 0、震 250、停 200、再震 250」（毫秒）。 */
        val VIBRATION_PATTERN: LongArray = longArrayOf(0L, 250L, 200L, 250L)
    }
}
