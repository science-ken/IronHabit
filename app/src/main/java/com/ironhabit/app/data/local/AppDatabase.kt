package com.ironhabit.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ironhabit.app.data.local.dao.BodyMetricDao
import com.ironhabit.app.data.local.dao.CheckInDao
import com.ironhabit.app.data.local.dao.ExerciseDao
import com.ironhabit.app.data.local.dao.FoodDao
import com.ironhabit.app.data.local.dao.HabitDao
import com.ironhabit.app.data.local.dao.HabitLogDao
import com.ironhabit.app.data.local.dao.MealDao
import com.ironhabit.app.data.local.dao.MealItemDao
import com.ironhabit.app.data.local.dao.StatsDao
import com.ironhabit.app.data.local.dao.WeekPlanDao
import com.ironhabit.app.data.local.entity.BodyMetricEntity
import com.ironhabit.app.data.local.entity.CheckInEntity
import com.ironhabit.app.data.local.entity.Converters
import com.ironhabit.app.data.local.entity.ExerciseEntity
import com.ironhabit.app.data.local.entity.FoodEntity
import com.ironhabit.app.data.local.entity.FoodServingEntity
import com.ironhabit.app.data.local.entity.HabitEntity
import com.ironhabit.app.data.local.entity.HabitLogEntity
import com.ironhabit.app.data.local.entity.MealEntity
import com.ironhabit.app.data.local.entity.MealItemEntity
import com.ironhabit.app.data.local.entity.WeekPlanEntity
import com.ironhabit.app.domain.model.DatabaseInfo

/**
 * IronHabit 本地数据库声明（Room）。
 *
 * - 7 张实体表 + 1 组 TypeConverter。
 * - `exportSchema = true`：schema JSON 输出到 `app/schemas/`，纳入版本管理。
 * - 版本 2：由 [MIGRATION_1_2] 从 v1 升级（逐组打卡 bitmask / RPE / 动作三态来源 / 多肌群 /
 *   习惯目标值 / 计划用户改动标记）。
 * - 版本 3：由 [MIGRATION_2_3] 从 v2 升级（新增 `meals` 表，饮食模块；**纯建表**）。
 *   **不启用破坏性迁移**（项目红线「禁破坏性迁移」）：`DatabaseModule` 未注册任何
 *   `fallbackToDestructiveMigration*`；遇到未注册的降级 schema 变化会**抛异常暴露**而非静默清库。
 * - 版本 6：由 [MIGRATION_5_6] 从 v5 升级（动作停用入口下线，存量停用动作一次性全部置回启用）。
 * - 版本 7：由 [MIGRATION_6_7] 从 v6 升级（`exercises` 增加 `equipment` 器械列，**纯加列无回填**）。
 * - 版本 8：由 [MIGRATION_7_8] 从 v7 升级（新增 `foods` + `food_servings` 两张表，食物库；**纯建表**，
 *   不碰 `meals` 一个字节 —— 饮食区"计划 vs 实际"的语义见 `.scratch/ironhabit-diet-food-log/spec.md` §1）。
 * - 版本 9：由 [MIGRATION_8_9] 从 v8 升级（新增 `meal_items`：一餐里**实际吃下的条目**。
 *   营养值是落库时算好的**快照**，改食物定义不会改写历史）。
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
        FoodEntity::class,
        FoodServingEntity::class,
        MealItemEntity::class,
    ],
    version = DatabaseInfo.SCHEMA_VERSION,
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

    abstract fun foodDao(): FoodDao

    abstract fun mealItemDao(): MealItemDao

    companion object {
        /** 数据库文件名。 */
        const val DATABASE_NAME: String = "ironhabit.db"

        // schema 版本住在 `domain/model/DatabaseInfo.SCHEMA_VERSION`：
        // 「我的」页要把这个数显示给用户，而 UI 不该直接看见 Room 的数据库类。
    }
}
