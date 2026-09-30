package com.ironhabit.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.ironhabit.app.data.notification.NotificationChannels
import com.ironhabit.app.di.ApplicationScope
import com.ironhabit.app.domain.repository.ReminderScheduler
import com.ironhabit.app.domain.usecase.SeedExercisesUseCase
import com.ironhabit.app.domain.usecase.SeedFoodsUseCase
import dagger.hilt.android.HiltAndroidApp
import java.security.KeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用入口 Application。
 *
 * 职责：
 * 1. 作为 Hilt 组件树的根（`@HiltAndroidApp`）。
 * 2. 启动时创建本地通知渠道（训练 / 习惯两条）。
 * 3. 幂等播种内置动作库与内置食物库（`OnConflictStrategy.IGNORE` / 同名跳过，重复启动不产生脏数据）。
 * 4. 依当前设置重排全部提醒（覆盖「应用更新 / 系统重启 / 进程被杀」后 AlarmManager
 *    已清空闹钟的情况 —— 系统在以上场景会丢弃所有已排闹钟，必须主动重建）。
 * 5. 注册时区 / 时钟变更的运行时广播接收器，变了就重排提醒。
 *
 * 说明：播种与重排都放到 [ApplicationScope] 子协程执行，避免阻塞主线程冷启动。
 * 本应用为纯离线单机应用，Application 内不做任何网络动作。
 */
@HiltAndroidApp
class IronHabitApp : Application() {

    /** 首启播种内置动作的用例（幂等）。 */
    @Inject
    lateinit var seedExercisesUseCase: SeedExercisesUseCase

    /** 首启播种内置食物库的用例（幂等，读 `assets/foods.json`）。 */
    @Inject
    lateinit var seedFoodsUseCase: SeedFoodsUseCase

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

            runCatching { seedFoodsUseCase() }
                .onFailure { Log.w(TAG, "seed foods failed", it) }

            runCatching { reminderScheduler.rescheduleAll() }
                .onFailure { Log.w(TAG, "reschedule reminders failed", it) }
        }

        // 5) 清除历史版本存 API Key 的加密存储（v2.0.14 删掉了整条直连通道）。
        applicationScope.launch { purgeLegacyAiCredentials() }

        // 4) 时区 / 时钟变更 → 重排提醒。
        // 为什么在代码里注册而不是只靠 manifest：实测（MuMu / Android 12）系统虽然按
        // `BootReceiver` 新增的 filter 找到了它，却回 `Background execution not allowed`
        // —— App 在后台时收不到这类隐式广播。而"进程一直开着、人飞到别的时区"
        // 恰恰是最需要重排的场景（AlarmManager 存的是绝对毫秒）。运行时注册不受该限制。
        // manifest 那份保留：进程未启动时（开机后）仍由它兜底。
        registerReceiver(
            clockChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * 删除 v2.0.13 及更早版本用来存 DeepSeek API Key 的加密文件与它的主密钥别名。
     *
     * **无条件、幂等**：文件不存在时 `deleteSharedPreferences` 返回 `false`、
     * Keystore 别名不存在时 `deleteEntry` 是 no-op，所以不立"已清除"标记位 ——
     * 为一次历史迁移留一个永久偏好键，代价比省下的这一次调用大。
     *
     * ⚠️ 必须在 IO 线程上跑（[ApplicationScope] 已是 IO）：Keystore 加载在部分机型上
     * 会阻塞几十毫秒以上，V2-P3-5 登记过这条，当初就是因为同类调用留在主线程上被判缺陷。
     *
     * 两步失败都只记日志：这些残留不影响任何功能（已没有代码读它），
     * 不该因为清不掉而让启动流程出错。
     */
    private fun purgeLegacyAiCredentials() {
        runCatching { deleteSharedPreferences(LEGACY_CREDENTIALS_FILE) }
            .onFailure { Log.w(TAG, "清除历史 API Key 文件失败", it) }
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(LEGACY_MASTER_KEY_ALIAS)
        }.onFailure { Log.w(TAG, "清除历史主密钥失败", it) }
    }

    /** 只转发给 [ReminderScheduler.rescheduleAll]，不自己算任何时间——判据必须只有一份。 */
    private val clockChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 留一行日志：这条路径平时不会走到，出问题时要能证明它到底有没有被投递到
            // （manifest 那份就是"看着配了、其实被系统拒投递"，靠日志才发现的）。
            Log.i(TAG, "时钟/时区变更（${intent.action}）→ 重排提醒")
            applicationScope.launch {
                runCatching { reminderScheduler.rescheduleAll() }
                    .onFailure { Log.w(TAG, "reschedule on ${intent.action} failed", it) }
            }
        }
    }

    private companion object {
        const val TAG: String = "IronHabitApp"

        /** 旧版 `AiCredentialsStore` 的加密文件名（必须与它当年的 `PREFS_FILE` 一致才删得掉）。 */
        const val LEGACY_CREDENTIALS_FILE: String = "ai_credentials"

        /** Android Keystore 的 provider 名。 */
        const val ANDROID_KEYSTORE: String = "AndroidKeyStore"

        /** `MasterKey.Builder().build()` 的默认别名（旧版未自定义 alias）。 */
        const val LEGACY_MASTER_KEY_ALIAS: String = "_androidx_security_master_key_"
    }
}
