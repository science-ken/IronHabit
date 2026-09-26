package com.ironhabit.app.ui.screens.food

import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Food
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 忌口筛选的纯逻辑 —— 挑菜弹层里唯一会"少显示东西"的那一块，必须单独钉住。
 *
 * 为什么这么测：`FoodLibraryViewModel` 的测试要起协程和 mock 档案流，而这几条判据
 * 全是纯函数，直接喂列表断言更准也更快（与 `searchFoods` 同一个取舍）。
 */
class FoodLibraryRestrictionTest {

    private fun food(id: Long, name: String, tags: Set<DietRestriction> = emptySet()) = Food(
        id = id,
        name = name,
        kcalPer100g = 100,
        proteinPer100g = 10.0,
        carbsPer100g = 10.0,
        fatPer100g = 1.0,
        dietaryTags = tags,
    )

    private val shrimp = food(1L, "虾仁", setOf(DietRestriction.SEAFOOD))
    private val milk = food(2L, "牛奶", setOf(DietRestriction.DAIRY))
    private val rice = food(3L, "米饭（蒸）")

    @Test
    fun noRestrictions_meansNothingIsHidden() {
        val state = FoodLibraryUiState(allFoods = listOf(rice, shrimp, milk), dietaryAvoid = emptySet())

        assertEquals(0, state.restrictedCount)
        assertEquals(listOf(rice, shrimp, milk), state.visibleFoods)
        assertTrue(state.visibleRestrictedFoods.isEmpty())
    }

    @Test
    fun matchedTag_isSunkOutOfTheMainList_notDeleted() {
        val state = FoodLibraryUiState(
            allFoods = listOf(rice, shrimp, milk),
            dietaryAvoid = setOf(DietRestriction.SEAFOOD),
        )

        assertEquals(1, state.restrictedCount)
        assertEquals("主列表里不该再有它，但它一条都没被删", listOf(rice, milk), state.visibleFoods)
        assertTrue("收起态不显示沉底那一栏", state.visibleRestrictedFoods.isEmpty())
        assertFalse(state.showRestricted)
    }

    @Test
    fun expandingTheChip_showsTheSunkSection_andSearchStillApplies() {
        val state = FoodLibraryUiState(
            allFoods = listOf(rice, shrimp, milk),
            dietaryAvoid = setOf(DietRestriction.SEAFOOD),
            showRestricted = true,
            query = "虾",
        )

        assertEquals(listOf(shrimp), state.visibleRestrictedFoods)
        assertTrue("搜索词把主列表清空了，但沉底那栏有命中 → 不算空态", !state.nothingToShow)
    }

    @Test
    fun chipCount_followsTheSearchWord() {
        val state = FoodLibraryUiState(
            allFoods = listOf(rice, shrimp, milk),
            dietaryAvoid = setOf(DietRestriction.SEAFOOD, DietRestriction.DAIRY),
            query = "虾",
        )

        assertEquals("chip 上的数字必须和展开后看到的对得上", 1, state.restrictedCount)
    }

    @Test
    fun untaggedFoodIsNeverBlocked_becauseMostOfTheLibraryHasNoTags() {
        // 这条是那句免责声明存在的全部理由：没标标签 ≠ 安全。
        assertFalse(isBlockedByAvoid(rice, setOf(DietRestriction.SEAFOOD)))
        assertTrue(isBlockedByAvoid(shrimp, setOf(DietRestriction.SEAFOOD)))
    }
}
