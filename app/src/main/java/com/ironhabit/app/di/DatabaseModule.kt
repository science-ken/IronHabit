package com.ironhabit.app.di

import android.content.Context
import androidx.room.Room
import com.ironhabit.app.data.local.AppDatabase
import com.ironhabit.app.data.local.MIGRATION_1_2
import com.ironhabit.app.data.local.MIGRATION_2_3
import com.ironhabit.app.data.local.dao.BodyMetricDao
import com.ironhabit.app.data.local.dao.CheckInDao
import com.ironhabit.app.data.local.dao.ExerciseDao
import com.ironhabit.app.data.local.dao.HabitDao
import com.ironhabit.app.data.local.dao.HabitLogDao
import com.ironhabit.app.data.local.dao.MealDao
import com.ironhabit.app.data.local.dao.StatsDao
import com.ironhabit.app.data.local.dao.WeekPlanDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room 数据库与各 DAO 的 Hilt 装配。
 *
 * 数据库名称固定为 [DATABASE_NAME]，落盘于应用私有目录，杀进程不丢数据。
 * 仅在**降级**时允许破坏性迁移（架构 §7.6）；正式升级走显式 Migration。
 *
 * ⚠️ **复数 API `.addMigrations(...)`**：Room 2.6.1 只有 `addMigrations`（复数），没有
 * `addMigration`（单数）。v1 设备升级需要 `1→2` 路径、v2 设备需要 `2→3` 路径，
 * **两个都必须注册** —— 只注册 `MIGRATION_2_3` 会让 v1 设备升级时找不到 `1→2` 而崩溃。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** 数据库文件名（与 T02 的 AppDatabase 保持一致）。 */
    private const val DATABASE_NAME = "ironhabit.db"

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        DATABASE_NAME,
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()

    @Provides
    fun provideExerciseDao(db: AppDatabase): ExerciseDao = db.exerciseDao()

    @Provides
    fun provideWeekPlanDao(db: AppDatabase): WeekPlanDao = db.weekPlanDao()

    @Provides
    fun provideCheckInDao(db: AppDatabase): CheckInDao = db.checkInDao()

    @Provides
    fun provideHabitDao(db: AppDatabase): HabitDao = db.habitDao()

    @Provides
    fun provideHabitLogDao(db: AppDatabase): HabitLogDao = db.habitLogDao()

    @Provides
    fun provideBodyMetricDao(db: AppDatabase): BodyMetricDao = db.bodyMetricDao()

    @Provides
    fun provideStatsDao(db: AppDatabase): StatsDao = db.statsDao()

    @Provides
    fun provideMealDao(db: AppDatabase): MealDao = db.mealDao()
}
