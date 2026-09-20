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

/**
 * v5 → v6：动作「启用 / 停用」入口下线（动作库的开关改成「加入计划」的 +）。
 *
 * 用户拍板**彻底砍掉停用功能**：界面上不再有停用入口，`exercises.is_active` 退化为
 * 永真标记（列保留，不做 `DROP COLUMN` —— 红线：minSdk 24 禁 DROP COLUMN；
 * 备份合同 `BackupPayload` 的字段也原样保留，老备份照常恢复）。
 *
 * 本次迁移是**一次性数据正本清源**：把历史上被停用过的动作全部置回 `is_active = 1` ——
 * 否则那些动作会因"无 UI 入口恢复"而永久消失（AI 排不出、计划里也不显示）。
 * `UPDATE` 无 SQLite 版本要求，红线安全。
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE exercises SET is_active = 1")
    }
}

/**
 * v6 → v7：`exercises` 增加**器械列**（`equipment`），让"这个动作你现在能不能练"有据可依。
 *
 * ## 为什么要有这一步
 * 动作实体此前只有分类（自重/力量/有氧），所以本地规则引擎判断器械约束只能按分类猜：
 * `STRENGTH` 一律要求"用户至少有一件力量器械"。于是「绳索下压」会排给只有哑铃的人，
 * 而「哑铃卧推」和「腿举」在规则眼里完全等价。`LocalRuleAdvisor` 自己的类文档里就写着
 * 「将来给 `exercises` 增加器械字段后，只需替换 `equipmentAllowed` 一处」—— 本次就是那一步。
 *
 * ## 这一步做什么
 * 只有一条 `ADD COLUMN equipment TEXT DEFAULT NULL`，**没有任何 UPDATE / 回填**：
 * `NULL` 的语义是**未标注**（存量行、用户自建动作），规则引擎遇到未标注就回落到旧的分类判据，
 * 因此老用户升级后**排出来的计划与升级前逐字一致**（内置动作的标注在启动播种时才补上，
 * 见 `DatabaseSeeder` 的"只补空、不覆盖"）。
 *
 * 约束（与其它迁移同）：`minSdk = 24` → 禁止 `DROP COLUMN` / `RENAME COLUMN`，本次只加列，天然满足。
 * ⚠️ DDL 必须与 KSP 由 [com.ironhabit.app.data.local.entity.ExerciseEntity] 生成的
 * `app/schemas/.../7.json` 的 `createSql` 逐字对齐，否则运行时 schema 校验会报
 * "Migration didn't properly handle exercises"。
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE exercises ADD COLUMN equipment TEXT DEFAULT NULL")
    }
}

/**
 * v7 → v8：新增食物库两张表（`foods` + `food_servings`）。**纯建表，不碰任何既有表。**
 *
 * ## 为什么 `meals` 一个字节都不动
 * 这一刀只建"能选出一样食物"的能力，**不建"吃了什么"**（那是第二刀的 `meal_items`）。
 * `meals.kcal` / `items_text` 继续是**计划**值 —— 见
 * `.scratch/ironhabit-diet-food-log/spec.md` §1：「`meal_items` 里有行 = 真的吃了」，
 * 而 AI 建议永远不进那张表。这条语义是本次设计里唯一不能妥协的一条，
 * 因为它正是训练区 2026-09-20 刚修掉的「排了计划当成练了」在饮食区的对应物。
 *
 * ## 为什么份量单独一张表，而不是主表上的一对列
 * 一个食物可以有「一碗 200g / 一盘 350g / 半份 100g」多套家用份量。
 * 用列表达（FitBook 那种 `servingWeight1G…9G`）要预先钉死"最多几套"，
 * 而 `minSdk = 24` 下**加列容易、永远删不掉**；子表加一行才是自然操作。
 *
 * ## 约束
 * `minSdk = 24` → 禁止 `DROP COLUMN` / `RENAME COLUMN`；本次只有 `CREATE TABLE` / `CREATE INDEX`，天然满足。
 * ⚠️ DDL 必须与 KSP 由 [com.ironhabit.app.data.local.entity.FoodEntity] /
 * [com.ironhabit.app.data.local.entity.FoodServingEntity] 生成的 `app/schemas/.../8.json`
 * 的 `createSql` **逐字对齐**（含外键子句末尾 `CASCADE )` 那个空格 —— Room 就是这么吐的），
 * 否则运行时 schema 校验会报 "Migration didn't properly handle foods"。
 * 两表都**不带 `DEFAULT` 子句**：实体上没有 `@ColumnInfo(defaultValue = …)`，
 * Kotlin 侧的默认值 Room 是看不见的，写了就会和生成结果不一致。
 *
 * Room 升级路径（链式）：
 * ```
 *   v1 ──► 1→2 ──► … ──► 6→7 ──► 7→8 ──► v8
 *   v7 ──► 7→8 ──► v8
 *   全新安装 ──► 直接按实体建表（不跑迁移）
 * ```
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `foods` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`kcal_per_100g` INTEGER NOT NULL, " +
                "`protein_per_100g` REAL NOT NULL, " +
                "`carbs_per_100g` REAL NOT NULL, " +
                "`fat_per_100g` REAL NOT NULL, " +
                "`dietary_tags` TEXT, " +
                "`source` TEXT NOT NULL, " +
                "`note` TEXT, " +
                "`is_active` INTEGER NOT NULL, " +
                "`is_user_edited` INTEGER NOT NULL, " +
                "`sort_order` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_foods_name` " +
                "ON `foods` (`name`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_foods_is_active` " +
                "ON `foods` (`is_active`)"
        )

        // 子表必须在主表之后建：外键指向 foods(id)。
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `food_servings` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`food_id` INTEGER NOT NULL, " +
                "`unit` TEXT NOT NULL, " +
                "`grams` INTEGER NOT NULL, " +
                "`sort_order` INTEGER NOT NULL, " +
                "FOREIGN KEY(`food_id`) REFERENCES `foods`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_food_servings_food_id` " +
                "ON `food_servings` (`food_id`)"
        )
    }
}

/**
 * v8 → v9：新增 `meal_items`（一餐里"实际吃了什么"的条目）。
 *
 * ## 语义承重墙
 * 这张表**只装真的吃下去的东西**。AI 生成的建议留在 `meals.items_text`，
 * 用户点「吃了它」才会往这里复制一行。
 * 让建议直接进这张表 = 把训练区 2026-09-20 刚修掉的
 * 「排了计划当成练了」在饮食区重演一遍（见 `.scratch/ironhabit-diet-food-log/spec.md` §1）。
 *
 * ## 为什么带 4 个营养快照列
 * 存"每 100g 定义 + 克数"然后查询时现算，会让改食物定义**改写历史**：
 * 把米饭从 116 改成 130，三个月前那顿跟着变，趋势图自己动。
 * 与 `check_ins.weight_kg` 存"当时举了多少"同一个原则。
 * 同时存 `food_name` 快照：食物被停用或将来被删，这一行仍然自解释。
 *
 * ## 两个外键的方向不一样，是有意的
 * - `meal_id` → `CASCADE`：一餐被删，它下面的条目当然跟着没；
 * - `food_id` → `SET_NULL`：删掉一个食物**绝不能**抹掉吃过它的历史记录。
 *   置空之后名称与营养快照仍在，只是点不进详情。
 *
 * 约束（与其它迁移同）：`minSdk = 24` → 禁 `DROP COLUMN` / `RENAME COLUMN`，本次纯建表。
 * ⚠️ DDL 必须与 KSP 生成的 `app/schemas/.../9.json` 的 `createSql` **逐字对齐**
 * （含两个外键子句之间那个 `, ` 与末尾 `SET NULL )` 前的空格），
 * 否则运行时校验会报 "Migration didn't properly handle meal_items"。
 * 列上**不写 `DEFAULT`**：实体没有 `@ColumnInfo(defaultValue = …)`，写了就不一致。
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `meal_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`meal_id` INTEGER NOT NULL, " +
                "`food_id` INTEGER, " +
                "`food_name` TEXT NOT NULL, " +
                "`grams` REAL NOT NULL, " +
                "`serving_unit` TEXT, " +
                "`serving_count` REAL, " +
                "`kcal` INTEGER NOT NULL, " +
                "`protein_g` REAL NOT NULL, " +
                "`carbs_g` REAL NOT NULL, " +
                "`fat_g` REAL NOT NULL, " +
                "`sort_order` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`meal_id`) REFERENCES `meals`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`food_id`) REFERENCES `foods`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_meal_items_meal_id` " +
                "ON `meal_items` (`meal_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_meal_items_food_id` " +
                "ON `meal_items` (`food_id`)"
        )
    }
}
