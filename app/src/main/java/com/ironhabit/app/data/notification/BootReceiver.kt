package com.ironhabit.app.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ironhabit.app.di.ApplicationScope
import com.ironhabit.app.domain.repository.ReminderScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 系统事件广播接收器（架构 §4.3）：开机 / 应用更新 / **时区与时钟变更**。
 *
 * 监听 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`（及部分厂商的 `QUICKBOOT_POWERON`）后，
 * 调 [ReminderScheduler.rescheduleAll] 依设置重排全部提醒——保证重启后提醒不丢。
 *
 * 同一套重排也用于 `TIMEZONE_CHANGED` / `TIME_SET` / `DATE_CHANGED`：
 * `AlarmManager` 存的是**绝对毫秒**，"每天 07:30"这种墙上时间必须在时区/时钟变了之后重算，
 * 否则从 +08 飞到 +01，提醒会在当地 00:30 响。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            -> Unit

            else -> return
        }

        val pendingResult = goAsync()
        applicationScope.launch {
            // 崩不崩已经由 `@ApplicationScope` 的 handler 兜住，这一层管的是**现场**：
            // 全局那条日志不会带上是哪一次广播（开机 / 更新 / 时区变更）失败了，
            // 而"提醒没响"这类问题只差这一句就查不出来。
            // 顺带对齐 `IronHabitApp` —— 同一个 `rescheduleAll()` 在那两处本来就包着。
            try {
                reminderScheduler.rescheduleAll()
            } catch (throwable: Exception) {
                Log.w(TAG, "重排提醒失败（${intent.action}）", throwable)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /** 部分厂商 ROM 的快速开机广播。 */
        const val ACTION_QUICKBOOT_POWERON: String = "android.intent.action.QUICKBOOT_POWERON"

        private const val TAG: String = "BootReceiver"
    }
}
