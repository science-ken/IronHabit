package com.ironhabit.app.di

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
     * 计划 / 建议来源（`docs/ai-coach-local.md` §6.1）。
     *
     * 本期**只有一个实现**：[LocalRuleAdvisor]（`source = LOCAL_RULES`，完全离线）。
     * 将来接入联网模型时，**只改这一处**（改为注入 `RemoteLlmAdvisor` 或按设置二选一），
     * UI 与 UseCase **零改动**。
     */
    @Provides
    @Singleton
    fun providePlanAdvisor(): PlanAdvisor = LocalRuleAdvisor
}
