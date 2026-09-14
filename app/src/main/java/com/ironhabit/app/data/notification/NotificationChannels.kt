package com.ironhabit.app.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.ironhabit.app.R
import com.ironhabit.app.domain.repository.ReminderType

/**
 * 本地通知渠道定义与创建（训练 / 习惯两条）。
 *
 * 渠道文案取自 `strings.xml`（T01 已提供），本对象**只读**资源、不新增。
 * 渠道创建幂等：重复调用会以同名渠道覆盖，安全。
 */
object NotificationChannels {

    /** 训练提醒渠道 id。 */
    const val CHANNEL_TRAINING: String = "ironhabit_training"

    /** 习惯提醒渠道 id。 */
    const val CHANNEL_HABIT: String = "ironhabit_habit"

    /**
     * 创建全部通知渠道（`IronHabitApp.onCreate` 首启调用）。
     * API 26 以下无需渠道，直接返回。
     */
    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(trainingChannel(context))
        manager.createNotificationChannel(habitChannel(context))
    }

    /** 提醒类型 → 渠道 id。 */
    fun channelIdFor(type: ReminderType): String = when (type) {
        ReminderType.TRAINING -> CHANNEL_TRAINING
        ReminderType.HABIT -> CHANNEL_HABIT
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun trainingChannel(context: Context): NotificationChannel =
        NotificationChannel(
            CHANNEL_TRAINING,
            context.getString(R.string.notif_channel_training_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_training_desc)
            enableVibration(true)
        }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun habitChannel(context: Context): NotificationChannel =
        NotificationChannel(
            CHANNEL_HABIT,
            context.getString(R.string.notif_channel_habit_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_habit_desc)
            enableVibration(true)
        }
}
