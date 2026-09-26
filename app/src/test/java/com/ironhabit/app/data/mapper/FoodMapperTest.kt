package com.ironhabit.app.data.mapper

import com.ironhabit.app.data.local.dao.FoodWithServings
import com.ironhabit.app.data.local.entity.FoodEntity
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodSource
import com.ironhabit.app.domain.model.FoodServing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FoodMapper] 的 `source` / 忌口标签编解码。
 *
 * ## 为什么这一份测试必须存在
 * `foods.source` 是 `TEXT NOT NULL` **但没有 CHECK**（`MIGRATION_7_8`），也就是说
 * "这一列只会是枚举名"这件事**完全由映射层保证**，数据库不兜底。
 * 食物库新增第三态 `AI_SUGGESTED`（外部 AI 饮食导入建库那一刀）之前，这里一条测试都没有，
 * 于是"读不懂的串回落成什么"这个决定从来没有被钉住过。
 *
 * ## 钉住的三条
 * 1. 三个枚举名原样往返 —— [FoodSource.AI_SUGGESTED] 那几条要能在食物库卡片上显示成「外部 AI 估」，
 *    回落错了就变成"自建"，而那是两种不同的可信度。
 * 2. **不认识的串回落 [FoodSource.CUSTOM]**，不是 `BUILT_IN`：回落成内置会让播种把用户自己的东西改写。
 * 3. 忌口标签是安全字段，CSV 往返不能丢项。
 */
class FoodMapperTest {

    private fun row(
        source: String,
        dietaryTags: String? = null,
    ) = FoodWithServings(
        food = FoodEntity(
            id = 1L,
            name = "紫薯",
            kcalPer100g = 60,
            proteinPer100g = 1.6,
            carbsPer100g = 13.0,
            fatPer100g = 0.1,
            dietaryTags = dietaryTags,
            source = source,
        ),
        servings = emptyList(),
    )

    @Test
    fun toDomain_readsEveryKnownSourceName_byItself() {
        for (source in FoodSource.entries) {
            assertEquals(source, FoodMapper.toDomain(row(source.name)).source)
        }
    }

    @Test
    fun toDomain_externalAiRow_isNotReadAsUserCreated() {
        // 这一条看着和上一条重复，留着的原因很具体：旧 APK 会把这行读成"自建"，
        // 新 APK 读错一次就是「外部 AI 估」这块标记从此消失，值得单独钉。
        assertEquals(
            FoodSource.AI_SUGGESTED,
            FoodMapper.toDomain(row(FoodSource.AI_SUGGESTED.name)).source,
        )
    }

    @Test
    fun toDomain_unknownSourceString_fallsBackToCustom_notBuiltIn() {
        // 方向不能反：回落成 BUILT_IN = 播种有权改写这一行，而它其实是用户自己的东西。
        assertEquals(FoodSource.CUSTOM, FoodMapper.toDomain(row("BUILTIN")).source)
        assertEquals(FoodSource.CUSTOM, FoodMapper.toDomain(row("")).source)
    }

    @Test
    fun toEntity_writesTheEnumNameVerbatim_soRoundTrips() {
        val food = Food(
            id = 0L,
            name = " 紫薯 ",
            kcalPer100g = 60,
            proteinPer100g = 1.6,
            carbsPer100g = 13.0,
            fatPer100g = 0.1,
            source = FoodSource.AI_SUGGESTED,
        )

        val entity = FoodMapper.toEntity(food).first

        assertEquals(FoodSource.AI_SUGGESTED.name, entity.source)
        assertEquals("紫薯", entity.name)
        // 往返相等的前提是名字已被映射层 trim（写库口子上 trim，读回来就不会带空格）。
        assertEquals(food.copy(name = "紫薯"), FoodMapper.toDomain(row(entity.source).copy(food = entity)))
    }

    @Test
    fun dietaryTags_surviveTheCsvRoundTrip() {
        // 忌口命中是整条挡的安全判据，标签在编解码里丢一项就等于那一餐放行了。
        val tags = setOf(DietRestriction.SEAFOOD, DietRestriction.GLUTEN)
        val entity = FoodMapper.toEntity(
            Food(
                id = 0L,
                name = "虾仁馄饨",
                kcalPer100g = 120,
                proteinPer100g = 6.0,
                carbsPer100g = 15.0,
                fatPer100g = 4.0,
                dietaryTags = tags,
            ),
        ).first

        assertEquals("SEAFOOD,GLUTEN", entity.dietaryTags)
        assertEquals(tags, FoodMapper.toDomain(row(entity.source, entity.dietaryTags)).dietaryTags)
    }

    @Test
    fun dietaryTags_emptySetWritesNull_notAnEmptyString() {
        // "空串"和 NULL 两种空会让读侧必须判两处（与 exercises.equipment 同一条口径）。
        val entity = FoodMapper.toEntity(
            Food(
                id = 0L,
                name = "米饭",
                kcalPer100g = 116,
                proteinPer100g = 2.6,
                carbsPer100g = 25.0,
                fatPer100g = 0.3,
            ),
        ).first

        assertTrue(entity.dietaryTags.isNullOrEmpty())
        assertTrue(FoodMapper.toDomain(row(entity.source, entity.dietaryTags)).dietaryTags.isEmpty())
    }

    @Test
    fun normalizeServings_dropsIncompleteAndDuplicatedUnits() {
        // 建库表单不填份量（food_servings 允许空），但一旦填了就不能造出"点得动却算不出营养"的行。
        val servings = listOf(
            FoodServing(id = 0L, unit = " 碗 ", grams = 200),
            FoodServing(id = 0L, unit = "碗", grams = 350),   // 同名第二份：先到先得，丢
            FoodServing(id = 0L, unit = "盘", grams = 0),      // 0 克的份会把营养算成 0
            FoodServing(id = 0L, unit = "", grams = 150),      // 没单位
        )

        val normalized = FoodMapper.normalizeServings(servings, foodId = 7L)

        assertEquals(listOf("碗"), normalized.map { serving -> serving.unit })
        assertEquals(200, normalized.single().grams)
        assertEquals(7L, normalized.single().foodId)
    }
}
