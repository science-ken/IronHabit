package com.ironhabit.app

import android.app.Application
import android.util.Log
import com.ironhabit.app.data.notification.NotificationChannels
import com.ironhabit.app.di.ApplicationScope
import com.ironhabit.app.domain.repository.ReminderScheduler
import com.ironhabit.app.domain.usecase.SeedExercisesUseCase
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用入口 Application。
 *
 * 职责：
 * 1. 作为 Hilt 组件树的根（`@HiltAndroidApp`）。
 * 2. 启动时创建本地通知渠道（训练 / 习惯两条）。
 * 3. 幂等播种内置动作库（`OnConflictStrategy.IGNORE`，重复启动不产生脏数据）。
 * 4. 依当前设置重排全部提醒（覆盖「应用更新 / 系统重启 / 进程被杀」后 AlarmManager
 *    已清空闹钟的情况 —— 系统在以上场景会丢弃所有已排闹钟，必须主动重建）。
 *
 * 说明：播种与重排都放到 [ApplicationScope] 子协程执行，避免阻塞主线程冷启动。
 * 本应用为纯离线单机应用，Application 内不做任何网络动作。
 */
@HiltAndroidApp
class IronHabitApp : Application() {

    /** 首启播种内置动作的用例（幂等）。 */
    @Inject
    lateinit var seedExercisesUseCase: SeedExercisesUseCase

    /** 提醒调度器（由 NotificationModule @Binds 为 ReminderSchedulerImpl）。 */
    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    /** 与应用同生命周期的协程作用域（SupervisorJob + IoDispatcher）。 */
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        // 1) 创建通知渠道（API 26+ 必需；低版本调用为空实现，安全）
        NotificationChannels.createAll(this)

        // 2) 幂等播种内置动作库（失败可忽略，下次启动会重试）
        // 3) 重排提醒（读设置 + 习惯表，失败仅记日志，不影响启动）
        applicationScope.launch {
            runCatching { seedExercisesUseCase() }
                .onFailure { Log.w(TAG, "seed exercises failed", it) }

            runCatching { reminderScheduler.rescheduleAll() }
                .onFailure { Log.w(TAG, "reschedule reminders failed", it) }
        }
    }

    private companion object {
        const val TAG: String = "IronHabitApp"
    }
}
