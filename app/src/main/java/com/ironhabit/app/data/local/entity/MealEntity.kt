package com.ironhabit.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `meals` 表：**每日实例**的一餐（不是周模板，见 `docs/schema-v3-meals.md` §3）。
 *
 * 三条设计要点（详见设计文档）：
 * 1. **无外键** —— 饮食与训练互不引用，没有需要级联的对象（比 `week_plans` 更简单，§5.2）；
 * 2. **`items_text` 用换行分隔文本** —— 条目只有文本、无独立 kcal/蛋白，无查询需求，
 *    故不做子表、不用 JSON（沿用 v2 `muscle_group` 存展示型列表的做法，§2.2①）；
 * 3. **`UNIQUE(date_epoch_day, meal_type)`** —— 带来幂等生成 + 软删后「加回同一餐」的精确定位
 *    （代价：一天不能有两条同类餐，§2.2②，§10-2 已确认）。
 *
 * ⚠️ **`is_active` / `is_user_edited` 的软删口径**（与 v2 完全一致，§2.3）：
 * - 用户「这餐不吃」→ `is_active = 0` **且** `is_user_edited = 1`（**不物理删**，`DELETE` 会被重新生成复活）；
 * - 用户改一餐 → `is_user_edited = 1`；
 * - 重新生成 → 跳过 `is_user_edited = 1` 的行（**含 `is_active = 0` 的软删行**）；
 * - 命中已存在行 → **显式 upsert**（`UPDATE` / `INSERT`），**禁用 `OnConflictStrategy.REPLACE`**。
 *
 * @property id 主键，自增
 * @property dateEpochDay 日期口径 = `LocalDate.toEpochDays()`，与 `check_ins`/`habit_logs` 同口径
 * @property mealType `BREAKFAST`/`LUNCH`/`SNACK`/`DINNER`（[com.ironhabit.app.domain.model.MealType.name]）
 * @property itemsText 多条食物条目（`\n` 分隔；条目内不含换行）
 * @property kcal 整餐热量
 * @property proteinG 整餐蛋白质（克）
 * @property isCompleted 勾选完成（对应预览 `done`）
 * @property sortOrder 同日内排序（默认取 `mealType` ordinal）
 * @property isActive 软删除标记（`0` = 用户「不吃这餐」）
 * @property isUserEdited 用户改过/删过 → `1`；重新生成时整行跳过
 * @property createdAt 创建时间戳（UTC 毫秒）
 */
@Entity(
    tableName = "meals",
    indices = [
        Index(value = ["date_epoch_day"]),
        Index(value = ["date_epoch_day", "meal_type"], unique = true),
    ],
)
data class MealEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "date_epoch_day")
    val dateEpochDay: Long = 0L,

    @ColumnInfo(name = "meal_type")
    val mealType: String = "",

    @ColumnInfo(name = "items_text", defaultValue = "''")
    val itemsText: String = "",

    @ColumnInfo(name = "kcal", defaultValue = "0")
    val kcal: Int = 0,

    @ColumnInfo(name = "protein_g", defaultValue = "0")
    val proteinG: Double = 0.0,

    @ColumnInfo(name = "is_completed", defaultValue = "0")
    val isCompleted: Boolean = false,

    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "is_active", defaultValue = "1")
    val isActive: Boolean = true,

    @ColumnInfo(name = "is_user_edited", defaultValue = "0")
    val isUserEdited: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)
