package com.ironhabit.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * v1 → v2：支持逐组打卡 / RPE / 动作三态来源 / 多肌群 / 备注 / 习惯目标值 / 计划用户改动标记。
 *
 * 约束：`minSdk = 24`，**禁止**使用 `DROP COLUMN`（需 SQLite 3.35 / API 31）与
 * `RENAME COLUMN`（需 SQLite 3.25 / API 28）。故本次一律 `ADD COLUMN`；
 * 废弃列（[ExerciseEntity.isBuiltIn]）只停止读写，不做物理删除。
 *
 * ⚠️ 不变量**有条件成立**：当存量 `completed_sets ≤ 31` 时，迁移后该行
 * `completed_sets == completed_sets_mask.countOneBits()`；当 `completed_sets > 31` 时，
 * mask 只能容纳低 31 位（`Int.MAX_VALUE`，popcount = 31），`completed_sets` 会被按 31
 * **截断登记** —— 此时该行**不再满足严格相等**（原值 > popcount），属已知、有意的截断。
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- exercises：三态来源 + 备注 ----
        db.execSQL(
            "ALTER TABLE exercises ADD COLUMN source TEXT NOT NULL DEFAULT 'BUILT_IN'"
        )
        db.execSQL(
            "ALTER TABLE exercises ADD COLUMN note TEXT DEFAULT NULL"
        )
        // 存量回填：原 is_built_in = 1 → BUILT_IN；否则（用户自建）→ CUSTOM。
        // v1 无 AI_SUGGESTED 数据，故不产生该值。is_built_in 列保留但停止读写。
        db.execSQL(
            "UPDATE exercises SET source = " +
                "CASE WHEN is_built_in = 1 THEN 'BUILT_IN' ELSE 'CUSTOM' END"
        )

        // ---- check_ins：逐组完成 bitmask + RPE ----
        db.execSQL(
            "ALTER TABLE check_ins ADD COLUMN completed_sets_mask INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE check_ins ADD COLUMN rpe INTEGER DEFAULT NULL"
        )
        // 存量回填：把「做了 n 组」展开为低 n 位全 1。
        // 当 n ≤ 31 时满足不变量 completed_sets == completed_sets_mask.countOneBits()；
        // 当 n > 31 时按 31 截断（mask = Int.MAX_VALUE，popcount = 31），此时
        // completed_sets（原值）≠ countOneBits()，为已知、有意的截断（历史组数登记为 31）。
        // MIN(...,31) 同时防止 `1 << 31` 触到符号位。
        db.execSQL(
            "UPDATE check_ins SET completed_sets_mask = " +
                "CASE WHEN completed_sets > 0 " +
                "THEN (1 << MIN(completed_sets, 31)) - 1 " +
                "ELSE 0 END"
        )

        // ---- habits：备注 + 目标值 ----
        db.execSQL(
            "ALTER TABLE habits ADD COLUMN note TEXT DEFAULT NULL"
        )
        db.execSQL("ALTER TABLE habits ADD COLUMN target_value REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE habits ADD COLUMN target_unit TEXT DEFAULT NULL")

        // ---- week_plans：用户手动改过标记（行级）----
        // 默认 0 = 现有计划均视为未被用户改过，AI 可自由覆盖。
        db.execSQL(
            "ALTER TABLE week_plans ADD COLUMN is_user_edited INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * v2 → v3：新增 `meals` 表（饮食模块，`docs/schema-v3-meals.md`）。
 *
 * **纯建表**：无数据变换、无回填 —— 本迁移**不可能因存量数据出错**（这正是选择独立
 * `Migration(2, 3)` 而非并入 `MIGRATION_1_2` 的原因；见设计文档 §4）。
 *
 * 约束（与 [MIGRATION_1_2] 同）：`minSdk = 24`，**禁止** `DROP COLUMN` / `RENAME COLUMN`
 * —— 本迁移只 `CREATE TABLE` / `CREATE INDEX`，天然满足。
 *
 * ⚠️ DDL 与 KSP 由 [MealEntity] 生成的 `app/schemas/.../3.json` 的 `createSql` **逐字对齐**
 * （含反引号、`IF NOT EXISTS`、`DEFAULT` 的确切形态）。任何一方改动都必须在同一次提交内同步。
 *
 * Room 升级路径（链式，两个 Migration 都已注册于 `DatabaseModule`）：
 * ```
 *   v1 ──► 1→2（v2：改列+回填）──► 2→3（v3：纯建表）──► v3
 *   v2 ──► 2→3 ──► v3
 *   全新安装 ──► 直接按实体建表（不跑迁移）
 * ```
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `meals` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`date_epoch_day` INTEGER NOT NULL, " +
                "`meal_type` TEXT NOT NULL, " +
                "`items_text` TEXT NOT NULL DEFAULT '', " +
                "`kcal` INTEGER NOT NULL DEFAULT 0, " +
                "`protein_g` REAL NOT NULL DEFAULT 0, " +
                "`is_completed` INTEGER NOT NULL DEFAULT 0, " +
                "`sort_order` INTEGER NOT NULL DEFAULT 0, " +
                "`is_active` INTEGER NOT NULL DEFAULT 1, " +
                "`is_user_edited` INTEGER NOT NULL DEFAULT 0, " +
                "`created_at` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_meals_date_epoch_day` " +
                "ON `meals` (`date_epoch_day`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_meals_date_epoch_day_meal_type` " +
                "ON `meals` (`date_epoch_day`, `meal_type`)"
        )
    }
}

/**
 * v3 → v4：`week_plans` 支持「模板 + 某周专属」（P3）。
 *
 * 做两件事：
 * 1. `ADD COLUMN week_start_epoch_day INTEGER NOT NULL DEFAULT 0`
 *    —— `0` = 模板（每周循环，也就是升级前的行为），> 0 = 只属于那一周（周一 epochDay）。**存量行全部是模板**，所以默认值就是回填值，不需要额外 `UPDATE`；
 * 2. **重建唯一索引**：把 `UNIQUE(day_of_week, exercise_id)` 换成
 *    `UNIQUE(day_of_week, exercise_id, week_start_epoch_day)`。
 *    不换的话，"模板里排深蹲"和"下周专属里也排深蹲"会撞同一个唯一槽位 —— 而这是合法状态。
 *
 * ## 为什么用哨兵 `0` 而不是 `NULL`
 * `ALTER TABLE ... DEFAULT NULL` 也能加列，但 SQLite 的**唯一索引把 NULL 视为互不相等**：
 * `(1, 7, NULL)` 可以插任意多行 → 同「天 × 动作」会出现多条模板行，
 * 直接打破 v1 起就有的"同槽位只有一行"不变量。`0` 不在真实数据里出现（epochDay 0 = 1970-01-01），
 * 当哨兵安全。这也是与预览稿（写的是 `DEFAULT NULL`）的**有意偏差**。
 *
 * 约束（与其它迁移同）：`minSdk = 24` → 只 `ADD COLUMN` / 改索引，
 * **`DROP COLUMN` / `RENAME COLUMN` 一律不用**；`DROP INDEX` / `CREATE INDEX` 无版本要求。
 *
 * ⚠️ 索引名必须与 Room 按 [WeekPlanEntity] 生成的默认名逐字一致，否则运行时 schema 校验会报
 * "Migration didn't properly handle week_plans"。
 *
 * Room 升级路径（链式，三个 Migration 都注册在 `DatabaseModule`）：
 * ```
 *   v1 ──► 1→2 ──► 2→3 ──► 3→4 ──► v4
 *   v2 ──► 2→3 ──► 3→4 ──► v4
 *   v3 ──► 3→4 ──► v4
 *   全新安装 ──► 直接按实体建表（不跑迁移）
 * ```
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- 1) 加列：存量行 = 模板（0），不需要回填 UPDATE ----
        db.execSQL(
            "ALTER TABLE week_plans ADD COLUMN week_start_epoch_day INTEGER NOT NULL DEFAULT 0"
        )

        // ---- 2) 唯一键加上"哪一周"这一维 ----
        db.execSQL("DROP INDEX IF EXISTS `index_week_plans_day_of_week_exercise_id`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_week_plans_day_of_week_exercise_id_week_start_epoch_day` " +
                "ON `week_plans` (`day_of_week`, `exercise_id`, `week_start_epoch_day`)"
        )
    }
}

/**
 * v4 → v5：把"每周循环"变成**每一周各自一份计划**（用户 2026-09-16 拍板）。
 *
 * ## 为什么要有这一步
 * v4 及以前，`week_plans` 里那份计划是**模板**：它自动套用到每一周，所以"下一周"永远和这一周
 * 一模一样，用户既看不到"下周没排计划"的状态，也没法只给某一周换安排。
 * 从 v5 起：**计划按周存放**，某一周没有计划就是没有 —— 界面只给一个「创建训练计划」入口
 * （自己创建 / 让 AI 生成）；想让某一份计划一直重复，用户显式勾「每周相同」。
 *
 * ## 这一步做什么
 * 把现有的"模板行"（`week_start_epoch_day = 0`）**落到当前这一周**（本周一）。
 * 于是：**你今天看到的还是同一份计划**（它变成"本周的计划"），而**从下周开始是空的**，
 * 需要你创建、让 AI 生成，或者勾「每周相同」让它一直重复 —— 正是用户要的行为。
 *
 * ⚠️ 迁移里读了一次**设备当前时间**（唯一一处这么做的地方）：这是一次性数据搬家，
 * 必须知道"现在是哪一周"。全新安装不会跑这条迁移（直接按实体建表）。
 *
 * 约束（与其它迁移同）：只 `UPDATE`，**没有任何 `DELETE` / `DROP COLUMN` / `RENAME COLUMN`**。
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {

    override fun migrate(db: SupportSQLiteDatabase) {
        val todayEpochDay: Long = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
            .toEpochDays()
            .toLong()
        // 周一取整：epochDay 0 = 1970-01-01（周四）→ 该周周一 = -3（公式与 BuildWeeklyReviewUseCase 同）。
        val weekStartEpochDay: Long =
            todayEpochDay - (((todayEpochDay + MONDAY_ALIGN_OFFSET) % DAYS_IN_WEEK + DAYS_IN_WEEK) % DAYS_IN_WEEK)

        db.execSQL(
            "UPDATE week_plans SET week_start_epoch_day = $weekStartEpochDay " +
                "WHERE week_start_epoch_day = 0"
        )
    }
}

/** 周一取整用的常量（与 `BuildWeeklyReviewUseCase` 同一套规则）。 */
private const val DAYS_IN_WEEK: Long = 7

/** epochDay `0`（周四）距其所在周周一的偏移。 */
private const val MONDAY_ALIGN_OFFSET: Long = 3
