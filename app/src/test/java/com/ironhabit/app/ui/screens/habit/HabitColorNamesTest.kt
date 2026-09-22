package com.ironhabit.app.ui.screens.habit

import com.ironhabit.app.R
import com.ironhabit.app.ui.theme.HABIT_COLOR_HEXES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 色板的颜色名（审查报告 P2-3 的无障碍半边）。
 *
 * 把 36dp 圆点补到 48dp 触摸区是**看得见**的部分；能不能被读出来是**看不见**的部分，
 * 只能靠这条测试钉住：每个预设色都必须有自己的名字。
 * 一旦哪个 hex 漏了映射，它会静默退回通用名「主题色」—— 不崩、不报错，
 * 只有用 TalkBack 的人遇到（一排都叫"主题色"的单选按钮）。所以必须在这里红。
 */
class HabitColorNamesTest {

    @Test
    fun everySwatchHasItsOwnColourName() {
        val names = HABIT_COLOR_HEXES.map(::colorNameRes)

        assertEquals(
            "色板有几个颜色就该有几个名字（撞名 = 念出两个一模一样的选项）",
            HABIT_COLOR_HEXES.size,
            names.toSet().size,
        )
        names.forEach { res ->
            assertNotEquals("有颜色没映射到名字，静默退回通用名", R.string.habit_color_generic, res)
        }
    }
}
