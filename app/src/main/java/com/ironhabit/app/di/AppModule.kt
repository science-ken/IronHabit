package com.ironhabit.app.di

import com.ironhabit.app.BuildConfig
import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.ai.DelegatingPlanAdvisor
import com.ironhabit.app.domain.ai.LocalRuleAdvisor
import com.ironhabit.app.domain.ai.PlanAdvisor
import com.ironhabit.app.domain.ai.remote.DeepSeekApi
import com.ironhabit.app.domain.ai.remote.DeepSeekClient
import com.ironhabit.app.domain.ai.remote.RemoteLlmAdvisor
import com.ironhabit.app.domain.repository.SettingsRepository
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

    /** 应用级协程作用域：SupervisorJob 保证单个子任务失败不影响其它任务。 */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** kotlinx-datetime 时钟（测试可替换）。 */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.System

    /** 系统时区（测试可替换）。 */
    @Provides
    @Singleton
    fun provideTimeZone(): TimeZone = TimeZone.currentSystemDefault()

    /**
     * 应用版本名（写入备份 JSON 的 `appVersion`，如 `"2.0"`）。
     *
     * 只在此处引用 [BuildConfig]，data 层通过 [AppVersion] 注入取值，保持 JVM 可测。
     */
    @Provides
    @Singleton
    @AppVersion
    fun provideAppVersion(): String = BuildConfig.VERSION_NAME

    /** DeepSeek HTTP 客户端（`HttpURLConnection`，零第三方 HTTP 依赖）。接口化便于单测注入 fake。 */
    @Provides
    @Singleton
    fun provideDeepSeekApi(): DeepSeekApi = DeepSeekClient()

    /** 远端顾问：解析是纯函数（JVM 单测直接测），本类只负责"提示词 → HTTP → 解析"。 */
    @Provides
    @Singleton
    fun provideRemoteLlmAdvisor(
        api: DeepSeekApi,
        credentialsStore: AiCredentialsStore,
    ): RemoteLlmAdvisor = RemoteLlmAdvisor(api = api, credentials = credentialsStore)

    /**
     * 计划 / 建议来源（联网一期）。
     *
     * 绑定不变（仍是 [PlanAdvisor] 接口），实现从「直接本地」换成**委托切换**：
     * [DelegatingPlanAdvisor] 按设置开关 + Key 配置决定走远端（[RemoteLlmAdvisor]）还是
     * 本地（[LocalRuleAdvisor]），远端任何失败自动回落本地。
     * UseCase / UI 零改动（这正是当初做 PlanAdvisor 抽象的目的）。
     */
    @Provides
    @Singleton
    fun providePlanAdvisor(
        credentialsStore: AiCredentialsStore,
        settingsRepository: SettingsRepository,
        remoteLlmAdvisor: RemoteLlmAdvisor,
    ): PlanAdvisor = DelegatingPlanAdvisor(
        local = LocalRuleAdvisor,
        remote = remoteLlmAdvisor,
        credentials = credentialsStore,
        settingsRepository = settingsRepository,
    )
}
