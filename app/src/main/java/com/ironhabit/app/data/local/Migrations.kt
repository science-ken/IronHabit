package com.ironhabit.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
