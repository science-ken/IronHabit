package com.ironhabit.app.ui.screens.food

import com.ironhabit.app.domain.model.Food
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 食物库的纯筛选（[searchFoods] / [sortByPinyin]）。
 *
 * 与 `TrainExerciseSearchTest` 同形：喂真列表、断真结果，不起 ViewModel。
 */
class FoodLibrarySearchTest {

    private fun food(name: String) = Food(
        name = name,
        kcalPer100g = 100,
        proteinPer100g = 1.0,
        carbsPer100g = 1.0,
        fatPer100g = 1.0,
    )

    private val library = listOf("苹果", "鸡蛋", "牛奶", "酸奶", "香蕉").map(::food)

    @Test
    fun blankQueryReturnsEverything() {
        // 反例：空查询返回空列表 —— 打开弹层会看到"食物库是空的"，而它其实有 29 条。
        assertEquals(library.size, searchFoods(library, "").size)
        assertEquals(library.size, searchFoods(library, "   ").size)
    }

    @Test
    fun matchesBySubstringAnywhere() {
        assertEquals(listOf("鸡蛋"), searchFoods(library, "鸡").map { it.name })
        assertEquals(listOf("牛奶", "酸奶"), searchFoods(library, "奶").map { it.name })
    }

    @Test
    fun surroundingWhitespaceDoesNotBreakMatching() {
        assertEquals(listOf("牛奶"), searchFoods(library, "  牛奶  ").map { it.name })
    }

    @Test
    fun noMatchReturnsEmptyNotNull() {
        assertTrue(searchFoods(library, "火锅").isEmpty())
    }

    @Test
    fun sortByPinyinOrdersChineseByName() {
        // 按码位排是「香蕉(U+9999) < 苹果(U+82F9) < 牛奶(U+725B)」的乱序；
        // 按拼音应是 牛(niú) < 苹(píng) < 香(xiāng)。
        val ordered = sortByPinyin(listOf("香蕉", "牛奶", "苹果").map(::food)).map { it.name }
        assertEquals(listOf("牛奶", "苹果", "香蕉"), ordered)
    }
}
