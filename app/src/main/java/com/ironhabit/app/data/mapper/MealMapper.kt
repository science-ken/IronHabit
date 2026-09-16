package com.ironhabit.app.data.mapper

import com.ironhabit.app.data.local.entity.MealEntity
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType

/**
 * `MealEntity ⇄ domain.Meal` 互转。
 *
 * **`items_text` ⇄ `List<String>` 的切分 / 拼装在此集中完成**（只此一处职责，
 * 与 v2 `muscle_group ⇄ List<String>` 同一做法，见设计文档 §2.2①）。
 *
 * 诚实登记的边界：条目内不能包含换行符（食物条目不可能是多行，风险 ≈ 0）；
 * 空条目（纯空白）在读写两侧都会被丢弃，避免出现空行。
 */
object MealMapper {

    /** 条目分隔符（换行）。 */
    private const val ITEM_SEPARATOR: String = "\n"

    /** 实体 → 领域模型。 */
    fun toDomain(entity: MealEntity): Meal = Meal(
        id = entity.id,
        dateEpochDay = entity.dateEpochDay,
        mealType = parseMealType(entity.mealType),
        items = splitItems(entity.itemsText),
        kcal = entity.kcal,
        proteinG = entity.proteinG,
        isCompleted = entity.isCompleted,
        sortOrder = entity.sortOrder,
        isActive = entity.isActive,
        isUserEdited = entity.isUserEdited,
        createdAt = entity.createdAt,
    )

    /** 领域模型 → 实体。 */
    fun toEntity(domain: Meal): MealEntity = MealEntity(
        id = domain.id,
        dateEpochDay = domain.dateEpochDay,
        mealType = domain.mealType.name,
        itemsText = joinItems(domain.items),
        kcal = domain.kcal,
        proteinG = domain.proteinG,
        isCompleted = domain.isCompleted,
        sortOrder = domain.sortOrder,
        isActive = domain.isActive,
        isUserEdited = domain.isUserEdited,
        createdAt = domain.createdAt,
    )

    /** 餐次名 → [MealType]；未知/空值回落 [MealType.BREAKFAST]（不崩）。 */
    fun parseMealType(raw: String): MealType =
        MealType.entries.firstOrNull { it.name == raw } ?: MealType.BREAKFAST

    /** `items_text` → 条目列表（去空白、丢空行）。 */
    fun splitItems(text: String): List<String> =
        text.split(ITEM_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /** 条目列表 → `items_text`（去空白、丢空行）。 */
    fun joinItems(items: List<String>): String =
        items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(ITEM_SEPARATOR)
}
