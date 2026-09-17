package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.WeekPlan
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeekPlanWeekResolver] 纯 JVM 单测（P3：模板 + 某周专属，**逐天覆盖**语义）。
 *
 * 这一组用例锁死的是**"哪一天到底练什么"的判定规则** —— 它决定了用户改过的东西会不会突然消失，
 * 所以每条规则都必须有断言（修复 B-1 后的口径：**每一天独立判定，日/周视图共用同一规则**）：
 * 1. 这一天没有该周专属行 → 回落模板（= P3 之前的行为，老用户无感）；
 * 2. 这一天有该周专属行 → 用专属行；**同周其他天不受影响**（没有专属的天照常回落模板 ——
 *    这是与旧「整周为单位」语义的本质区别，B-1 的矛盾根源就在这）；
 * 3. 软删除行永远不参与（模板和专属都一样）；
 * 4. 排序稳定（sortOrder → id）；
 * 5. 别的周的专属行不会串台。
 */
class WeekPlanWeekResolverTest {

    private val thisWeek: Long = LocalDate(2026, 9, 14).toEpochDays().toLong()   // 周一
    private val nextWeek: Long = thisWeek + 7
    private val lastWeek: Long = thisWeek - 7

    private fun template(
        id: Long,
        exerciseId: Long,
        dayOfWeek: Int,
        sortOrder: Int = 0,
        isActive: Boolean = true,
    ): WeekPlan = WeekPlan(
        id = id,
        exerciseId = exerciseId,
        dayOfWeek = dayOfWeek,
        sortOrder = sortOrder,
        isActive = isActive,
        weekStartEpochDay = WeekPlan.TEMPLATE_WEEK_START,
    )

    private fun override(
        id: Long,
        exerciseId: Long,
        dayOfWeek: Int,
        weekStartEpochDay: Long,
        sortOrder: Int = 0,
        isActive: Boolean = true,
    ): WeekPlan = WeekPlan(
        id = id,
        exerciseId = exerciseId,
        dayOfWeek = dayOfWeek,
        sortOrder = sortOrder,
        isActive = isActive,
        weekStartEpochDay = weekStartEpochDay,
    )

    // ---------------- 1. 这一天没有专属 → 模板 ----------------

    @Test
    fun noOverride_fallsBackToTemplate() {
        val rows = listOf(
            template(id = 1L, exerciseId = 11L, dayOfWeek = 1),
            template(id = 2L, exerciseId = 12L, dayOfWeek = 1),
            template(id = 3L, exerciseId = 13L, dayOfWeek = 3),
        )

        val monday = WeekPlanWeekResolver.effectiveForDay(rows, dayOfWeek = 1, weekStartEpochDay = thisWeek)

        assertEquals("模板行原样返回（P3 之前的行为不能被改坏）", listOf(1L, 2L), monday.map { it.id })
    }

    @Test
    fun onlyOtherWeeksHaveOverride_stillUsesTemplate() {
        val rows = listOf(
            template(id = 1L, exerciseId = 11L, dayOfWeek = 1),
            override(id = 9L, exerciseId = 99L, dayOfWeek = 1, weekStartEpochDay = nextWeek),
        )

        val monday = WeekPlanWeekResolver.effectiveForDay(rows, dayOfWeek = 1, weekStartEpochDay = thisWeek)

        assertEquals("别的周的专属行不许串台", listOf(1L), monday.map { it.id })
    }

    // ---------------- 2. 逐天覆盖：改哪天只影响哪天（B-1 核心语义）----------------

    @Test
    fun overrideForOneDay_otherDaysStillFallBackToTemplate() {
        val rows = listOf(
            template(id = 1L, exerciseId = 11L, dayOfWeek = 1),
            template(id = 2L, exerciseId = 12L, dayOfWeek = 3),
            template(id = 3L, exerciseId = 13L, dayOfWeek = 5),
            // 本周只把周一改了
            override(id = 20L, exerciseId = 77L, dayOfWeek = 1, weekStartEpochDay = thisWeek),
        )

        assertEquals(
            "周一：有该周专属 → 用专属行",
            listOf(20L),
            WeekPlanWeekResolver.effectiveForDay(rows, dayOfWeek = 1, weekStartEpochDay = thisWeek).map { it.id },
        )
        assertEquals(
            "周三：没给这天排专属 → 回落模板（B-1 修复核心：不再因『本周有专属』而整周变空）",
            listOf(2L),
            WeekPlanWeekResolver.effectiveForDay(rows, dayOfWeek = 3, weekStartEpochDay = thisWeek).map { it.id },
        )
        assertEquals(
            "周五：同理回落模板",
            listOf(3L),
            WeekPlanWeekResolver.effectiveForDay(rows, dayOfWeek = 5, weekStartEpochDay = thisWeek).map { it.id },
        )
        assertEquals(
            "上周没受影响：照旧用模板",
            listOf(1L),
            WeekPlanWeekResolver.effectiveForDay(rows, dayOfWeek = 1, weekStartEpochDay = lastWeek).map { it.id },
        )
    }

    @Test
    fun separateWeeks_doNotInterfere() {
        val rows = listOf(
            template(id = 1L, exerciseId = 11L, dayOfWeek = 1),
            override(id = 20L, exerciseId = 77L, dayOfWeek = 1, weekStartEpochDay = thisWeek),
            override(id = 30L, exerciseId = 88L, dayOfWeek = 1, weekStartEpochDay = nextWeek),
        )

        assertEquals(
            listOf(20L),
            WeekPlanWeekResolver.effectiveForDay(rows, 1, thisWeek).map { it.id },
        )
        assertEquals(
            listOf(30L),
            WeekPlanWeekResolver.effectiveForDay(rows, 1, nextWeek).map { it.id },
        )
        assertEquals(
            "更早的周（两个专属都不匹配）→ 回模板",
            listOf(1L),
            WeekPlanWeekResolver.effectiveForDay(rows, 1, lastWeek).map { it.id },
        )
    }

    // ---------------- 3. 软删除行永远不参与 ----------------

    @Test
    fun softDeletedRows_areNeverReturned() {
        val rows = listOf(
            template(id = 1L, exerciseId = 11L, dayOfWeek = 1),
            template(id = 2L, exerciseId = 12L, dayOfWeek = 1, isActive = false),
            override(id = 20L, exerciseId = 77L, dayOfWeek = 1, weekStartEpochDay = thisWeek, isActive = false),
        )

        // 软删的专属行不算"这一天有专属" → 回落模板；模板里的软删行也不返回。
        assertEquals(
            "用户删掉的行（模板或专属）一律不出现",
            listOf(1L),
            WeekPlanWeekResolver.effectiveForDay(rows, 1, thisWeek).map { it.id },
        )
    }

    @Test
    fun onlySoftDeletedOverride_fallsBackToTemplate() {
        val rows = listOf(
            template(id = 1L, exerciseId = 11L, dayOfWeek = 1),
            override(id = 20L, exerciseId = 77L, dayOfWeek = 1, weekStartEpochDay = thisWeek, isActive = false),
        )

        assertEquals(
            "只剩软删的专属行 = 这一天没有专属 → 回落模板",
            listOf(1L),
            WeekPlanWeekResolver.effectiveForDay(rows, 1, thisWeek).map { it.id },
        )
    }

    // ---------------- 4. 排序稳定 ----------------

    @Test
    fun resultIsSortedBySortOrderThenId() {
        val rows = listOf(
            template(id = 5L, exerciseId = 11L, dayOfWeek = 1, sortOrder = 2),
            template(id = 6L, exerciseId = 12L, dayOfWeek = 1, sortOrder = 0),
            template(id = 7L, exerciseId = 13L, dayOfWeek = 1, sortOrder = 0),
        )

        val monday = WeekPlanWeekResolver.effectiveForDay(rows, 1, thisWeek)

        assertEquals(
            "sortOrder 升序，同序按 id 升序（保证同输入同输出）",
            listOf(6L, 7L, 5L),
            monday.map { it.id },
        )
    }

    @Test
    fun otherDaysAreNotMixedIn() {
        val rows = listOf(
            template(id = 1L, exerciseId = 11L, dayOfWeek = 1),
            template(id = 2L, exerciseId = 12L, dayOfWeek = 2),
            template(id = 3L, exerciseId = 13L, dayOfWeek = 7),
        )

        assertEquals(listOf(1L), WeekPlanWeekResolver.effectiveForDay(rows, 1, thisWeek).map { it.id })
        assertEquals(listOf(3L), WeekPlanWeekResolver.effectiveForDay(rows, 7, thisWeek).map { it.id })
    }

    // ---------------- 5. 整周视图（生成预览用）：逐天混合 ----------------

    @Test
    fun effectiveForWeek_mixesOverrideAndTemplate_dayByDay() {
        val rows = listOf(
            template(id = 1L, exerciseId = 11L, dayOfWeek = 5),
            template(id = 2L, exerciseId = 12L, dayOfWeek = 1),
            override(id = 20L, exerciseId = 77L, dayOfWeek = 3, weekStartEpochDay = thisWeek),
            override(id = 21L, exerciseId = 78L, dayOfWeek = 1, weekStartEpochDay = thisWeek, sortOrder = 1),
            override(id = 22L, exerciseId = 79L, dayOfWeek = 1, weekStartEpochDay = thisWeek, sortOrder = 0),
        )

        val week = WeekPlanWeekResolver.effectiveForWeek(rows, thisWeek)

        assertEquals(
            "逐天覆盖：周一用专属（22/21），周二空，周三用专属（20），周五回落模板（1）" +
                "—— 按 星期 → sortOrder → id 排序",
            listOf(22L, 21L, 20L, 1L),
            week.map { it.id },
        )
    }

    @Test
    fun effectiveForWeek_withoutOverride_returnsTemplate() {
        val rows = listOf(
            template(id = 1L, exerciseId = 11L, dayOfWeek = 5),
            template(id = 2L, exerciseId = 12L, dayOfWeek = 1),
            template(id = 3L, exerciseId = 13L, dayOfWeek = 1, isActive = false),
        )

        assertEquals(
            "没有专属 → 整周视图就是模板（软删行已剔除）",
            listOf(2L, 1L),
            WeekPlanWeekResolver.effectiveForWeek(rows, thisWeek).map { it.id },
        )
    }

    @Test
    fun effectiveForWeek_dayAndWeekViewsAgree() {
        // B-1 回归守护：同一份数据，周视图逐天拆开的结论必须与日视图逐天查询完全一致。
        val rows = listOf(
            template(id = 1L, exerciseId = 11L, dayOfWeek = 1),
            template(id = 2L, exerciseId = 12L, dayOfWeek = 3),
            override(id = 20L, exerciseId = 77L, dayOfWeek = 1, weekStartEpochDay = thisWeek),
        )

        val weekView = WeekPlanWeekResolver.effectiveForWeek(rows, thisWeek)
        val dayByDay = (1..7).flatMap { day ->
            WeekPlanWeekResolver.effectiveForDay(rows, dayOfWeek = day, weekStartEpochDay = thisWeek)
        }

        assertEquals(
            "日视图与周视图共用同一判定规则，结论必须逐行一致",
            dayByDay.map { it.id },
            weekView.map { it.id },
        )
    }

    @Test
    fun emptyInput_isSafe() {
        assertEquals(emptyList<Long>(), WeekPlanWeekResolver.effectiveForDay(emptyList(), 1, thisWeek).map { it.id })
        assertEquals(emptyList<Long>(), WeekPlanWeekResolver.effectiveForWeek(emptyList(), thisWeek).map { it.id })
    }

    @Test
    fun templateSentinelIsZero_andNeverLooksLikeARealWeek() {
        // 哨兵不能撞真实周：epochDay 0 = 1970-01-01，永远不会是"某周的周一"出现在数据里。
        assertEquals("模板哨兵必须是 0", 0L, WeekPlan.TEMPLATE_WEEK_START)
        assertTrue(WeekPlan(weekStartEpochDay = WeekPlan.TEMPLATE_WEEK_START).isTemplate)
        assertFalse(WeekPlan(weekStartEpochDay = thisWeek).isTemplate)
    }
}
