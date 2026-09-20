package com.ironhabit.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `food_servings` 表：一个食物的**多套家用份量**（一碗 / 一盘 / 一个 / 半份）。
 *
 * 独立成表而不是在主表塞 18 列（FitBook 那种 `servingWeight1G…9G` 的写法）：
 * 列式方案要预先钉死"最多几套"，而 Room 里给已有表加列在 `minSdk = 24` 下只能 `ADD COLUMN`、
 * 永远删不掉；一张子表加一行是自然操作。
 *
 * ## 为什么 `ON DELETE CASCADE` 在这里是安全的
 * 本表只描述"这个食物有哪些份"，**不承载任何历史记录**：
 * 用户吃过的量落在第二刀的 `meal_items` 上，那里存的是**算完的克数与营养快照**，
 * 不引用本表的行 id。所以删食物不会、也不该牵动历史。
 *
 * ⚠️ 但**物理删除食物本身仍不提供**（见 `FoodDao.deactivate`）：删掉 `foods` 行会级联清空
 * 它的份量定义，让"当时那碗到底多少克"再也无法向用户解释 —— 尽管数值快照还在。
 */
@Entity(
    tableName = "food_servings",
    foreignKeys = [
        ForeignKey(
            entity = FoodEntity::class,
            parentColumns = ["id"],
            childColumns = ["food_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["food_id"])],
)
data class FoodServingEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "food_id")
    val foodId: Long,

    /** 单位名："碗 / 盘 / 个 / 勺"。同一食物内不应重复，去重口径见 `FoodMapper.normalizeServings`。 */
    @ColumnInfo(name = "unit")
    val unit: String,

    /** 该单位等于多少克。 */
    @ColumnInfo(name = "grams")
    val grams: Int,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
)
