package com.ironhabit.app.di

import android.app.AlarmManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.ironhabit.app.data.notification.ReminderSchedulerImpl
import com.ironhabit.app.domain.repository.ReminderScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 通知与提醒调度装配。
 *
 * - [NotificationManagerCompat]：发出提醒通知。
 * - [AlarmManager]：精确闹钟排期（到点提醒）。
 * - [ReminderScheduler] 绑定到 `ReminderSchedulerImpl`（AlarmManager 实现）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationModule {

    /** 把 domain 的提醒调度接口绑定到 AlarmManager 实现。 */
    @Binds
    @Singleton
    abstract fun bindReminderScheduler(impl: ReminderSchedulerImpl): ReminderScheduler

    companion object {

        /** 通知管理器（兼容各 API 版本）。 */
        @Provides
        @Singleton
        fun provideNotificationManagerCompat(
            @ApplicationContext context: Context,
        ): NotificationManagerCompat = NotificationManagerCompat.from(context)

        /** 系统闹钟服务（精确提醒依赖）。 */
        @Provides
        @Singleton
        fun provideAlarmManager(
            @ApplicationContext context: Context,
        ): AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
}
