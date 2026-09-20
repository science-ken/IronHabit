package com.ironhabit.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ironhabit.app.domain.model.FoodSource

/**
 * `foods` 表：食物库（内置 / 自建）。
 *
 * 索引：`name` 唯一（播种幂等 + 重名判定）、`is_active`（停用过滤）。
 *
 * ⚠️ 与 `exercises` 不同，本表**目前没有外键指向它**：
 * 计划条目表 `meal_items` 在第二刀才落地（见 `.scratch/ironhabit-diet-food-log/spec.md` §5）。
 * 届时它存的是**营养值与食物名的快照**，所以即使本表加 `ON DELETE CASCADE` 也不该被用来"清理历史"。
 */
@Entity(
    tableName = "foods",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["is_active"]),
    ],
)
data class FoodEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "kcal_per_100g")
    val kcalPer100g: Int,

    @ColumnInfo(name = "protein_per_100g")
    val proteinPer100g: Double,

    @ColumnInfo(name = "carbs_per_100g")
    val carbsPer100g: Double,

    @ColumnInfo(name = "fat_per_100g")
    val fatPer100g: Double,

    /**
     * `DietRestriction.name` 的**有序 CSV**（如 `"DAIRY"`、`"PEANUT,SEAFOOD"`）。
     *
     * `null` / 空 = **不含任何已登记的忌口成分**（与 `exercises.equipment` 的
     * "`null` = 未标注"**刻意不同**）：忌口是安全相关的判据，"没标注"必须往安全的一侧靠 = 视为无忌口，
     * 所以这里没有第三种状态，未勾选就是空。
     */
    @ColumnInfo(name = "dietary_tags")
    val dietaryTags: String? = null,

    /**
     * `FoodSource.name`。
     *
     * ⚠️ **刻意走原始 String + mapper 解码**（同 `MealEntity.meal_type`），而不是 `Converters` 里的
     * 枚举转换器：转换器的返回值可空，塞进非空列时遇到未知值就是运行时 NPE；
     * mapper 里可以显式选一个安全的兜底态（见 `FoodMapper`）。
     */
    @ColumnInfo(name = "source")
    val source: String = FoodSource.CUSTOM.name,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "is_user_edited")
    val isUserEdited: Boolean = false,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)
