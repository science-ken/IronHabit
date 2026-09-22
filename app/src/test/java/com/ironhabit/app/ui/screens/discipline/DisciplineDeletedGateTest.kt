package com.ironhabit.app.ui.screens.discipline

import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已删除」那一段的门控（A10：软删之后界面上再也找不回来）。
 *
 * 门控放在 [DisciplineUiState] 而不是界面里，是为了让"收起时不渲染、展开时才渲染"
 * 这件事可测 —— 与 `FoodLibraryUiState.visibleInactiveFoods` / `nothingToShow` 同一形状。
 * 最关键的是最后那条：**只剩已删除习惯时不能画成空态**，否则恢复入口又被藏起来了。
 */
class DisciplineDeletedGateTest {

    private fun habit(id: Long, active: Boolean) = Habit(id = id, name = "h$id", isActive = active)

    private fun item(id: Long) = HabitItem(habit = habit(id, active = true))

    @Test
    fun collapsedShowsNothingOfTheDeletedOnes() {
        val state = DisciplineUiState(
            isLoading = false,
            habits = listOf(item(1L)),
            deletedHabits = listOf(habit(2L, active = false), habit(3L, active = false)),
            showDeleted = false,
        )

        assertEquals(0, state.visibleDeletedHabits.size)
        assertFalse(state.nothingToShow)
    }

    @Test
    fun expandedShowsEveryDeletedOne() {
        val state = DisciplineUiState(
            isLoading = false,
            habits = listOf(item(1L)),
            deletedHabits = listOf(habit(2L, active = false), habit(3L, active = false)),
            showDeleted = true,
        )

        assertEquals(listOf(2L, 3L), state.visibleDeletedHabits.map { deleted -> deleted.id })
    }

    /** 启用列表空了、但已删除的还找得回来 —— 这时不能只给一个「还没有习惯」的空态。 */
    @Test
    fun onlyDeletedHabitsIsStillSomethingToShow() {
        val collapsed = DisciplineUiState(
            isLoading = false,
            deletedHabits = listOf(habit(2L, active = false)),
            showDeleted = false,
        )
        val expanded = collapsed.copy(showDeleted = true)

        // 收起时空态照常（用户没要求看它们），展开时空态必须让位给可恢复的那几行。
        assertTrue(collapsed.nothingToShow)
        assertFalse(expanded.nothingToShow)
    }
}
