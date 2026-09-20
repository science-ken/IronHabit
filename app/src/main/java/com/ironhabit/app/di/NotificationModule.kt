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

    /**
     * 把 domain 的提醒调度接口绑定到 AlarmManager 实现。
     *
     * ⚠️ **故意不加 `@Singleton`**：`AppModule.provideTimeZone()` 也刻意不是单例（B-5），
     * 但只要本绑定是单例，`ReminderSchedulerImpl` 里注入的那个 `TimeZone` 就会被冻在
     * "进程启动那一刻"——设备换了时区之后，"每天 07:30"仍按旧时区算，提醒会在当地半夜响。
     * 不作用域 = 每个注入点（每次广播 / 每个 ViewModel）新建一个实例，重新求值时区。
     * 该类无任何可变状态，多实例不会重复登记闹钟（闹钟按 `PendingIntent` 的 requestCode 去重）。
     */
    @Binds
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
