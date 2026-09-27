package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [mealSlotStates] —— 「编辑这一餐」那排餐次 chip 谁能点的判据（审查报告 P2-1）。
 *
 * `meals` 上有 `UNIQUE(date_epoch_day, meal_type)`，而弹层以前把四颗 chip 全渲染成可点：
 * 一天四餐齐全时把早餐改成午餐**必然**撞约束，用户先点一下、再回头读一句报错。
 * 判据抽成纯函数是为了把三种格子各自的走向钉死 —— 尤其第三种：
 * **软删行仍占槽，而它在列表里根本看不见**，这时既不能灰掉不解释（用户会以为 App 坏了），
 * 也不能报「请先删掉那一条」（他删不动一条看不见的一餐）。
 */
class MealSlotStatesTest {

    private fun meal(id: Long, type: MealType, isActive: Boolean = true) = Meal(
        id = id,
        dateEpochDay = 20_724L,
        mealType = type,
        items = listOf("鸡蛋 2 个"),
        kcal = 144,
        proteinG = 12.4,
        isActive = isActive,
    )

    @Test
    fun aFullDayLeavesEveryOtherSlotTaken() {
        val day = listOf(
            meal(1L, MealType.BREAKFAST),
            meal(2L, MealType.LUNCH),
            meal(3L, MealType.SNACK),
            meal(4L, MealType.DINNER),
        )

        val slots = mealSlotStates(day, excludeMealId = 1L)

        assertEquals(
            "正在编辑的那一餐自己占的槽位必须算空 —— 否则它自己那颗 chip 会被灰掉",
            MealSlotState.FREE,
            slots.getValue(MealType.BREAKFAST),
        )
        assertEquals(
            "四餐齐全时另外三格都活着：点了必然撞唯一索引，不该摆出来让人点",
            setOf(MealType.LUNCH, MealType.SNACK, MealType.DINNER),
            MealType.entries.filter { slots.getValue(it) == MealSlotState.TAKEN }.toSet(),
        )
    }

    /** 关键那一格：软删行看不见，但仍占唯一索引。 */
    @Test
    fun aSoftDeletedMealHoldsItsSlotButStaysSelectable() {
        val day = listOf(
            meal(1L, MealType.BREAKFAST),
            meal(2L, MealType.LUNCH, isActive = false),
        )

        val slots = mealSlotStates(day, excludeMealId = 1L)

        assertEquals(
            "被删过的午餐可以给点（写路径会把那一行复活并替换内容），但不能悄悄点",
            MealSlotState.DELETED,
            slots.getValue(MealType.LUNCH),
        )
        assertEquals(MealSlotState.FREE, slots.getValue(MealType.SNACK))
        assertEquals(MealSlotState.FREE, slots.getValue(MealType.DINNER))
    }

    @Test
    fun anEmptyDayOffersAllFour() {
        val slots = mealSlotStates(listOf(meal(1L, MealType.BREAKFAST)), excludeMealId = 1L)

        assertEquals(
            "只有一餐且正在编辑它 = 四颗都可点（AI 少排一餐的日子改类别是常见操作）",
            MealType.entries.map { it to MealSlotState.FREE }.toMap(),
            slots,
        )
    }

    /** 反向证据：不排掉自己，正在编辑的那一格会被标成"被别的餐占用"。 */
    @Test
    fun excludingNothingWouldGreyTheChipBeingEdited() {
        val day = listOf(meal(1L, MealType.BREAKFAST))

        assertEquals(
            "这条是在钉住 excludeMealId 参数的存在意义：漏了它，用户打开弹层看到的是自己那格是灰的",
            MealSlotState.TAKEN,
            mealSlotStates(day, excludeMealId = 0L).getValue(MealType.BREAKFAST),
        )
    }
}
