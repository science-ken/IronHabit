package com.ironhabit.app.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ironhabit.app.di.ApplicationScope
import com.ironhabit.app.domain.repository.ReminderScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新广播接收器（架构 §4.3）。
 *
 * 监听 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`（及部分厂商的 `QUICKBOOT_POWERON`）后，
 * 调 [ReminderScheduler.rescheduleAll] 依设置重排全部提醒——保证重启后提醒不丢。
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
            -> Unit

            else -> return
        }

        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                reminderScheduler.rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /** 部分厂商 ROM 的快速开机广播。 */
        const val ACTION_QUICKBOOT_POWERON: String = "android.intent.action.QUICKBOOT_POWERON"
    }
}
