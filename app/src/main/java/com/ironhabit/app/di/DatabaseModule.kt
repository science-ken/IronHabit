package com.ironhabit.app.di

import android.content.Context
import androidx.room.Room
import com.ironhabit.app.data.local.AppDatabase
import com.ironhabit.app.data.local.MIGRATION_1_2
import com.ironhabit.app.data.local.MIGRATION_2_3
import com.ironhabit.app.data.local.MIGRATION_3_4
import com.ironhabit.app.data.local.MIGRATION_4_5
import com.ironhabit.app.data.local.MIGRATION_5_6
import com.ironhabit.app.data.local.MIGRATION_6_7
import com.ironhabit.app.data.local.MIGRATION_7_8
import com.ironhabit.app.data.local.dao.BodyMetricDao
import com.ironhabit.app.data.local.dao.CheckInDao
import com.ironhabit.app.data.local.dao.ExerciseDao
import com.ironhabit.app.data.local.dao.FoodDao
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
 * 升级走显式 Migration（`1→2`、`2→3`、`3→4`）；**不注册任何破坏性回退** —— 依据项目红线「禁破坏性迁移」，
 * 一旦遇到未注册的（降级）schema 变化，Room 会**抛异常暴露**而非静默清空用户数据，
 * 对本地个人应用更安全、更诚实（历史遗留的 `fallbackToDestructiveMigrationOnDowngrade()` 已移除）。
 *
 * ⚠️ **复数 API `.addMigrations(...)`**：Room 2.6.1 只有 `addMigrations`（复数），没有
 * `addMigration`（单数）。v1 设备需要 `1→2`、v2 设备需要 `2→3`、v3 设备需要 `3→4`，
 * **每一条都必须注册** —— 少注册一条，对应版本的老设备升级时会直接崩。
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
        .addMigrations(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
            MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
        )
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

    @Provides
    fun provideFoodDao(db: AppDatabase): FoodDao = db.foodDao()
}
