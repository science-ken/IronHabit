package com.ironhabit.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ironhabit.app.data.local.dao.BodyMetricDao
import com.ironhabit.app.data.local.dao.CheckInDao
import com.ironhabit.app.data.local.dao.ExerciseDao
import com.ironhabit.app.data.local.dao.HabitDao
import com.ironhabit.app.data.local.dao.HabitLogDao
import com.ironhabit.app.data.local.dao.MealDao
import com.ironhabit.app.data.local.dao.StatsDao
import com.ironhabit.app.data.local.dao.WeekPlanDao
import com.ironhabit.app.data.local.entity.BodyMetricEntity
import com.ironhabit.app.data.local.entity.CheckInEntity
import com.ironhabit.app.data.local.entity.Converters
import com.ironhabit.app.data.local.entity.ExerciseEntity
import com.ironhabit.app.data.local.entity.HabitEntity
import com.ironhabit.app.data.local.entity.HabitLogEntity
import com.ironhabit.app.data.local.entity.MealEntity
import com.ironhabit.app.data.local.entity.WeekPlanEntity

/**
 * IronHabit 本地数据库声明（Room）。
 *
 * - 7 张实体表 + 1 组 TypeConverter。
 * - `exportSchema = true`：schema JSON 输出到 `app/schemas/`，纳入版本管理。
 * - 版本 2：由 [MIGRATION_1_2] 从 v1 升级（逐组打卡 bitmask / RPE / 动作三态来源 / 多肌群 /
 *   习惯目标值 / 计划用户改动标记）。
 * - 版本 3：由 [MIGRATION_2_3] 从 v2 升级（新增 `meals` 表，饮食模块；**纯建表**）。
 *   破坏性迁移仅在**降级**时启用（由 `DatabaseModule` 构建时的
 *   `fallbackToDestructiveMigrationOnDowngrade()` 提供）。
 */
@Database(
    entities = [
        ExerciseEntity::class,
        WeekPlanEntity::class,
        CheckInEntity::class,
        HabitEntity::class,
        HabitLogEntity::class,
        BodyMetricEntity::class,
        MealEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun exerciseDao(): ExerciseDao

    abstract fun weekPlanDao(): WeekPlanDao

    abstract fun checkInDao(): CheckInDao

    abstract fun habitDao(): HabitDao

    abstract fun habitLogDao(): HabitLogDao

    abstract fun bodyMetricDao(): BodyMetricDao

    abstract fun statsDao(): StatsDao

    abstract fun mealDao(): MealDao

    companion object {
        /** 数据库文件名。 */
        const val DATABASE_NAME: String = "ironhabit.db"

        /** 当前 schema 版本。 */
        const val VERSION: Int = 3
    }
}
