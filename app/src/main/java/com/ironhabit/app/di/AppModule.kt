package com.ironhabit.app.di

import android.util.Log
import com.ironhabit.app.BuildConfig
import com.ironhabit.app.domain.ai.LocalRuleAdvisor
import com.ironhabit.app.domain.ai.PlanAdvisor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import javax.inject.Singleton

/**
 * 全局基础能力装配：协程调度器、应用作用域、时钟与时区。
 *
 * 日期口径严格遵守架构 §7.3：统一使用注入的 [Clock] 与 [TimeZone]，
 * 便于单元测试替换为固定时钟，保证 streak 计算可复现。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** IO 调度器：数据库/文件 I/O。 */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** Default 调度器：纯计算（streak / 统计）。 */
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * 应用级协程作用域：[SupervisorJob] 保证单个子任务失败不影响其它任务，
     * [applicationCoroutineExceptionHandler] 保证失败的这一个不把进程带走。
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(
        SupervisorJob() + dispatcher + applicationCoroutineExceptionHandler { message, throwable ->
            Log.e(TAG_APPLICATION_SCOPE, message, throwable)
        },
    )

    /** kotlinx-datetime 时钟（测试可替换）。 */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.System

    /**
     * 系统时区（测试可替换）。
     *
     * B-5：**不做 `@Singleton`** —— 单例会把"App 启动那一刻"的时区快照住，
     * 用户旅行跨时区后「今天」仍然按旧时区算。每次注入点求值一次，
     * 保证长时间驻留（前台服务/常驻通知）后日期口径跟随系统变化。
     * `TimeZone` 是不可变值类型，重复求值无副作用。
     */
    @Provides
    fun provideTimeZone(): TimeZone = TimeZone.currentSystemDefault()

    /**
     * 应用版本名（写入备份 JSON 的 `appVersion`，如 `"2.0.1"`）。
     *
     * 只在此处引用 [BuildConfig]，data 层通过 [AppVersion] 注入取值，保持 JVM 可测。
     */
    @Provides
    @Singleton
    @AppVersion
    fun provideAppVersion(): String = BuildConfig.VERSION_NAME

    /**
     * 计划 / 建议的唯一实现：本地确定性规则。
     *
     * v2.0.14 删掉了「用户自填 API Key 直连 DeepSeek」那条通道，连同按开关与 Key 路由的
     * 委托层（`DelegatingPlanAdvisor`）一起 —— 所以这里回到**直接绑定**。
     * 真要再接任何远端，先重新决策"要不要联网、把哪些档案字段发出去"，
     * 而不是往这个接口上插一个实现就完事。
     */
    @Provides
    @Singleton
    fun providePlanAdvisor(): PlanAdvisor = LocalRuleAdvisor
}

/** 未捕获协程异常兜底日志的 tag（`logcat -s` 用得上）。 */
private const val TAG_APPLICATION_SCOPE: String = "AppScope"
