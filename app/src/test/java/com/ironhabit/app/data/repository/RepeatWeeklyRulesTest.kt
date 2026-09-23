package com.ironhabit.app.data.repository

import com.ironhabit.app.domain.model.WeekPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RepeatWeeklyRules] —— 复制一条计划行（成模板行 / 成另一周的专属行）时的字段合并。
 *
 * 起因是 2026-09-22 真机查出的一条静默数据破坏（D14）：开关从关到开，
 * 模板里 4 条 `is_user_edited=1` 的行全被复制成 0，于是下一次「生成计划」
 * 不再认为这些槽位有主 —— 用户手改过的内容可以被覆盖甚至"复活"。
 * 复制本身是对的（内容照抄本周），错的只有那两个标记被清掉。
 */
class RepeatWeeklyRulesTest {

    private val weekRow = WeekPlan(
        id = 63L,
        exerciseId = 6L,
        dayOfWeek = 1,
        targetSets = 4,
        targetReps = 8,
        createdAt = 1_700_000_000_000L,
    )

    private val nextWeekStart: Long = 20_724L

    private fun copyToTemplate(
        source: WeekPlan = weekRow,
        existing: WeekPlan? = null,
    ): WeekPlan = RepeatWeeklyRules.copyIntoWeek(
        source = source,
        existing = existing,
        weekStartEpochDay = WeekPlan.TEMPLATE_WEEK_START,
    )

    @Test
    fun copyLandsOnTheTemplateWeekAsAnActiveRow() {
        val copied = copyToTemplate()

        assertEquals("必须落到「每周相同」那份", WeekPlan.TEMPLATE_WEEK_START, copied.weekStartEpochDay)
        assertEquals("id 归 0 让 DAO 按槽位命中或自增", 0L, copied.id)
        assertTrue(copied.isActive)
        assertEquals("内容照抄本周", 4, copied.targetSets)
        assertEquals(8, copied.targetReps)
    }

    /** 「复制到下周」的全部意义：落到那一周，**不碰模板**。 */
    @Test
    fun copyIntoAnotherWeekLandsOnThatWeekNotOnTheTemplate() {
        val copied = RepeatWeeklyRules.copyIntoWeek(
            source = weekRow,
            existing = null,
            weekStartEpochDay = nextWeekStart,
        )

        assertEquals(nextWeekStart, copied.weekStartEpochDay)
        assertNotEquals("写进模板 = 无限期往后重复，正是要改掉的行为",
            WeekPlan.TEMPLATE_WEEK_START, copied.weekStartEpochDay)
    }

    /** D14 的回归钉子：目标槽位上原本受保护的行，复制之后必须**仍然**受保护。 */
    @Test
    fun existingTemplateRowKeepsItsUserEditedFlag() {
        val protectedTemplate = weekRow.copy(id = 51L, isUserEdited = true, isActive = false)

        val copied = copyToTemplate(existing = protectedTemplate)

        assertTrue("勾一次开关不得把用户手改标记清掉（清了 AI 就会覆盖它）", copied.isUserEdited)
    }

    /** 同一条规则也保护**下周**里用户手改过的行 —— 两份目标周共用一个函数，不该有差别。 */
    @Test
    fun existingNextWeekRowKeepsItsUserEditedFlag() {
        val handEditedNextWeekRow = weekRow.copy(
            id = 99L,
            weekStartEpochDay = nextWeekStart,
            isUserEdited = true,
        )

        val copied = RepeatWeeklyRules.copyIntoWeek(
            source = weekRow,
            existing = handEditedNextWeekRow,
            weekStartEpochDay = nextWeekStart,
        )

        assertTrue(copied.isUserEdited)
    }

    @Test
    fun userEditedWeekRowCarriesItsFlagIntoTheTemplate() {
        assertTrue(copyToTemplate(source = weekRow.copy(isUserEdited = true)).isUserEdited)
    }

    @Test
    fun plainCopyStaysPlain() {
        val copied = copyToTemplate(existing = weekRow.copy(id = 51L, isUserEdited = false))

        assertFalse("两边都没手改时不该凭空长出保护标记", copied.isUserEdited)
    }

    @Test
    fun existingCreationTimestampWinsOverAZeroFromTheSource() {
        val stampedTemplate = weekRow.copy(id = 56L, createdAt = 1_600_000_000_000L)

        val copied = copyToTemplate(
            source = weekRow.copy(createdAt = 0L),
            existing = stampedTemplate,
        )

        assertEquals(1_600_000_000_000L, copied.createdAt)
    }

    @Test
    fun blankTimestampOnBothSidesStaysBlank() {
        val copied = copyToTemplate(
            source = weekRow.copy(createdAt = 0L),
            existing = weekRow.copy(id = 56L, createdAt = 0L),
        )

        assertEquals(0L, copied.createdAt)
    }
}
