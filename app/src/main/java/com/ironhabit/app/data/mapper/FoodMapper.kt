package com.ironhabit.app.data.mapper

import com.ironhabit.app.data.local.dao.FoodWithServings
import com.ironhabit.app.data.local.entity.FoodEntity
import com.ironhabit.app.data.local.entity.FoodServingEntity
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodServing
import com.ironhabit.app.domain.model.FoodSource
import com.ironhabit.app.domain.model.decodeEnumSet

/**
 * [Food] ↔ [FoodEntity] + [FoodServingEntity]。
 *
 * 与 `MealMapper` 同属"纯映射、不做业务判断"。唯一例外是 [normalizeServings]：
 * 它守的是**数据完整性**不变量（单位为空、克数 ≤ 0、同名份重复都会造出一条
 * 点得动却算不出营养的行），放在映射层是因为**两条写库路径**（表单保存、播种补空）
 * 都必须过它 —— 只放表单里就会从播种漏掉。
 */
object FoodMapper {

    private const val TAG_SEPARATOR: String = ","

    fun toDomain(row: FoodWithServings): Food {
        val entity: FoodEntity = row.food
        return Food(
            id = entity.id,
            name = entity.name,
            kcalPer100g = entity.kcalPer100g,
            proteinPer100g = entity.proteinPer100g,
            carbsPer100g = entity.carbsPer100g,
            fatPer100g = entity.fatPer100g,
            servings = row.servings
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .map { serving ->
                    FoodServing(id = serving.id, unit = serving.unit, grams = serving.grams, sortOrder = serving.sortOrder)
                },
            dietaryTags = decodeTags(entity.dietaryTags),
            source = decodeSource(entity.source),
            note = entity.note,
            isActive = entity.isActive,
            isUserEdited = entity.isUserEdited,
            sortOrder = entity.sortOrder,
            createdAt = entity.createdAt,
        )
    }

    /** 主表行 + 待写入的份量行。返回的份量行 `foodId` 为 0，由仓库在拿到主表 rowid 后回填。 */
    fun toEntity(food: Food): Pair<FoodEntity, List<FoodServingEntity>> {
        val entity = FoodEntity(
            id = food.id,
            name = food.name.trim(),
            kcalPer100g = food.kcalPer100g,
            proteinPer100g = food.proteinPer100g,
            carbsPer100g = food.carbsPer100g,
            fatPer100g = food.fatPer100g,
            dietaryTags = encodeTags(food.dietaryTags),
            source = food.source.name,
            note = food.note,
            isActive = food.isActive,
            isUserEdited = food.isUserEdited,
            sortOrder = food.sortOrder,
            createdAt = food.createdAt,
        )
        return entity to normalizeServings(food.servings, foodId = 0L)
    }

    /**
     * 丢掉无效份、按单位去重（**先到先得**）、按顺序重排 `sortOrder`。
     *
     * 为什么先到先得而不是报错：播种补空时一个食物可能同时有内置份数和用户已填的同名份，
     * 用户那条一定排在前面（来自库里读出的既有行），报错会让冷启动直接失败。
     */
    fun normalizeServings(servings: List<FoodServing>, foodId: Long): List<FoodServingEntity> {
        val seenUnits = HashSet<String>()
        val result = ArrayList<FoodServingEntity>(servings.size)
        for (serving in servings) {
            val unit: String = serving.unit.trim()
            if (unit.isEmpty() || serving.grams <= 0) continue
            // 空单位/克数非正 = 无效；单位比较忽略大小写与首尾空白之外的差异。
            if (!seenUnits.add(unit)) continue
            result += FoodServingEntity(
                id = serving.id,
                foodId = foodId,
                unit = unit,
                grams = serving.grams,
                sortOrder = result.size,
            )
        }
        return result
    }

    /**
     * 未知值一律回落 [FoodSource.CUSTOM]。
     *
     * 方向要选对：`CUSTOM` = "这是用户自己的东西"，播种不会碰它、也不会把它当内置覆盖；
     * 若回落到 `BUILT_IN`，一个读坏的字符串就会让用户自建食物**被播种改写**。
     *
     * ⚠️ 这一条同时是"旧 APK 读新行"的实际行为：[FoodSource.AI_SUGGESTED] 落地之前打包的版本
     * 认不出这个串，会把外部 AI 建的那几条显示成"自建"（`foods.source` 无 CHECK，装得下任何串）。
     * 本刀接受这个后果、不加校验 —— 但它不是"看不见就没事"，见 `.scratch/ironhabit-external-diet-import/spec.md` §5.3。
     */
    private fun decodeSource(value: String?): FoodSource =
        value?.let { raw -> FoodSource.entries.firstOrNull { it.name == raw } } ?: FoodSource.CUSTOM

    private fun decodeTags(csv: String?): Set<DietRestriction> =
        if (csv.isNullOrBlank()) {
            emptySet()
        } else {
            decodeEnumSet<DietRestriction>(csv.split(TAG_SEPARATOR).mapTo(LinkedHashSet()) { it.trim() })
        }

    /** 空集写 `null` 而不是空串：与 `exercises.equipment` 一致，避免"空串"和"NULL"两种空。 */
    private fun encodeTags(tags: Set<DietRestriction>): String? =
        tags.takeIf { it.isNotEmpty() }?.joinToString(TAG_SEPARATOR) { it.name }
}
