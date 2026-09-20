package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.ironhabit.app.data.local.entity.FoodEntity
import com.ironhabit.app.data.local.entity.FoodServingEntity
import kotlinx.coroutines.flow.Flow

/** `foods` + 它的 `food_servings`，一次查询取回（Room 的 `@Relation` 会自己发第二条子查询）。 */
data class FoodWithServings(
    @Embedded val food: FoodEntity,

    @Relation(parentColumn = "id", entityColumn = "food_id")
    val servings: List<FoodServingEntity>,
)

/**
 * `foods` 表 DAO：食物库增删改查 + 播种。
 *
 * ⚠️ **禁用 `OnConflictStrategy.REPLACE`**（与 `ExerciseDao` / `CheckInDao` 同一条红线）：
 * `INSERT OR REPLACE` = DELETE + INSERT，rowid 会变。第二刀的 `meal_items.food_id` 一旦建立，
 * REPLACE 就会把历史条目的引用甩断 —— 所以从第一刀起就只允许 `ABORT` + 显式 [upsert]。
 *
 * 搜索**不在 SQL 层**：与动作库一致，取回启用列表后在内存里按名称做包含匹配。
 * 内置库只有几十到几百条，走 `LIKE` 反而要操心转义和大小写，不划算。
 */
@Dao
interface FoodDao {

    @Transaction
    @Query("SELECT * FROM foods WHERE is_active = 1 ORDER BY sort_order, name")
    fun observeActiveWithServings(): Flow<List<FoodWithServings>>

    /** 按 id 取单条（**含已停用** —— 历史记录要能点进来看当时吃的是什么）。 */
    @Transaction
    @Query("SELECT * FROM foods WHERE id = :id")
    suspend fun getWithServingsById(id: Long): FoodWithServings?

    @Query("SELECT * FROM foods WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): FoodEntity?

    /**
     * 批量按 `name` 取行（播种补空用：一次查询拿回全部同名行，避免逐条点查）。
     *
     * ⚠️ `names` 不能为空 —— 空列表会生成 `IN ()`，SQLite 视为语法错误。调用方自己保证非空。
     */
    @Query("SELECT * FROM foods WHERE name IN (:names)")
    suspend fun getByNames(names: List<String>): List<FoodEntity>

    /** 重名检查（排除自己）。唯一索引是最终防线，这个查询只是为了给出友好报错。 */
    @Query("SELECT COUNT(*) FROM foods WHERE name = :name AND id != :excludeId")
    suspend fun countByName(name: String, excludeId: Long): Int

    /** 单行插入（冲突即抛，**禁用 `REPLACE`**）。仅供 [upsert] 未命中路径使用。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: FoodEntity): Long

    /** 按主键整行更新（保住原 rowid）。 */
    @Update
    suspend fun update(entity: FoodEntity)

    /** 幂等批量插入：返回每行 rowId，被忽略的行为 `-1`。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<FoodEntity>): List<Long>

    /**
     * 幂等 upsert **主表行**，返回 rowid。
     *
     * ⚠️ **不管子表**：份量行的替换由 `FoodRepositoryImpl.upsert` 在同一个事务里做
     * （要 `AppDatabase.withTransaction`，DAO 默认的 `@Transaction` 嵌套语义不如显式事务清楚）。
     * 命中键：`id > 0` 时按主键，否则按唯一 `name`。
     */
    @Transaction
    suspend fun upsert(entity: FoodEntity): Long {
        val existing = if (entity.id > 0L) getById(entity.id) else getByName(entity.name)
        return if (existing == null) {
            insert(entity)
        } else {
            update(entity.copy(id = existing.id))
            existing.id
        }
    }

    @Query("SELECT * FROM foods WHERE id = :id")
    suspend fun getById(id: Long): FoodEntity?

    // ---- 子表：份量 ----

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertServings(entities: List<FoodServingEntity>)

    @Query("DELETE FROM food_servings WHERE food_id = :foodId")
    suspend fun deleteServings(foodId: Long)

    @Query("SELECT * FROM food_servings WHERE food_id = :foodId ORDER BY sort_order, id")
    suspend fun getServings(foodId: Long): List<FoodServingEntity>

    /**
     * 备份导出用：**全部**食物（含已停用）连同一份份量定义，一次取回。
     *
     * 必须含停用行 —— 历史条目可能正引用着它们；漏掉就是换机之后点不进详情。
     * 按 `id` 排序而不是按 `sort_order`：备份要的是**可复现的字节**，
     * 同一条记录导两次应当得到同一个 JSON。
     */
    @Transaction
    @Query("SELECT * FROM foods ORDER BY id")
    suspend fun getAllWithServings(): List<FoodWithServings>

    /** 备份恢复用：先 [clearAll] 再整表灌入，所以这里不需要幂等语义。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<FoodEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllServings(entities: List<FoodServingEntity>)

    /**
     * 备份恢复用（清空顺序见 `BackupRepositoryImpl`：先子表后主表）。
     *
     * ⚠️ 单列出来是因为 `food_servings` 对 `foods` 是 `ON DELETE CASCADE`：
     * 只 [clearAll] 主表在效果上等价，但那要靠"外键恰好开着"这个前提才成立。
     * 恢复是一次性动作，别把正确性押在隐式级联上。
     */
    @Query("DELETE FROM food_servings")
    suspend fun clearAllServings()

    @Query("DELETE FROM foods")
    suspend fun clearAll()

    /**
     * 停用（= 本表**唯一的**删除出口）。
     *
     * 物理 `DELETE` 在第一刀**不提供**：条目要引用食物，且"能不能真删"取决于有没有吃过它，
     * 那是第二刀 `meal_items` 落地后才有的信息。届时也应默认走停用。
     */
    @Query("UPDATE foods SET is_active = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)
}
