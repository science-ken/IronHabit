package com.ironhabit.app.di

import com.ironhabit.app.data.repository.BackupRepositoryImpl
import com.ironhabit.app.data.repository.BodyMetricRepositoryImpl
import com.ironhabit.app.data.repository.CheckInRepositoryImpl
import com.ironhabit.app.data.repository.ExerciseRepositoryImpl
import com.ironhabit.app.data.repository.HabitRepositoryImpl
import com.ironhabit.app.data.repository.MealRepositoryImpl
import com.ironhabit.app.data.repository.PlanRepositoryImpl
import com.ironhabit.app.data.repository.SettingsRepositoryImpl
import com.ironhabit.app.data.repository.StatsRepositoryImpl
import com.ironhabit.app.domain.repository.BackupRepository
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.repository.StatsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 仓库接口 → 实现的绑定（依赖倒置：domain 定义接口，data 提供实现）。
 *
 * 全部实现类位于 `com.ironhabit.app.data.repository`，由 T02 实现。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindExerciseRepository(impl: ExerciseRepositoryImpl): ExerciseRepository

    @Binds
    @Singleton
    abstract fun bindPlanRepository(impl: PlanRepositoryImpl): PlanRepository

    @Binds
    @Singleton
    abstract fun bindCheckInRepository(impl: CheckInRepositoryImpl): CheckInRepository

    @Binds
    @Singleton
    abstract fun bindHabitRepository(impl: HabitRepositoryImpl): HabitRepository

    @Binds
    @Singleton
    abstract fun bindStatsRepository(impl: StatsRepositoryImpl): StatsRepository

    @Binds
    @Singleton
    abstract fun bindBodyMetricRepository(impl: BodyMetricRepositoryImpl): BodyMetricRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository

    @Binds
    @Singleton
    abstract fun bindMealRepository(impl: MealRepositoryImpl): MealRepository
}
