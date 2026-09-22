package com.ironhabit.app.data.repository

import com.ironhabit.app.domain.model.WeekPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RepeatWeeklyRules] —— 勾选「每周相同」时一条复制的字段合并。
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

    @Test
    fun copyLandsOnTheTemplateWeekAsAnActiveRow() {
        val copied = RepeatWeeklyRules.copyIntoTemplate(source = weekRow, existing = null)

        assertEquals("必须落到「每周相同」那份", WeekPlan.TEMPLATE_WEEK_START, copied.weekStartEpochDay)
        assertEquals("id 归 0 让 DAO 按槽位命中或自增", 0L, copied.id)
        assertTrue(copied.isActive)
        assertEquals("内容照抄本周", 4, copied.targetSets)
        assertEquals(8, copied.targetReps)
    }

    /** D14 的回归钉子：模板槽位上原本受保护的行，复制之后必须**仍然**受保护。 */
    @Test
    fun existingTemplateRowKeepsItsUserEditedFlag() {
        val protectedTemplate = weekRow.copy(id = 51L, isUserEdited = true, isActive = false)

        val copied = RepeatWeeklyRules.copyIntoTemplate(source = weekRow, existing = protectedTemplate)

        assertTrue("勾一次开关不得把用户手改标记清掉（清了 AI 就会覆盖它）", copied.isUserEdited)
    }

    @Test
    fun userEditedWeekRowCarriesItsFlagIntoTheTemplate() {
        val copied = RepeatWeeklyRules.copyIntoTemplate(
            source = weekRow.copy(isUserEdited = true),
            existing = null,
        )

        assertTrue(copied.isUserEdited)
    }

    @Test
    fun plainCopyStaysPlain() {
        val copied = RepeatWeeklyRules.copyIntoTemplate(
            source = weekRow,
            existing = weekRow.copy(id = 51L, isUserEdited = false),
        )

        assertFalse("两边都没手改时不该凭空长出保护标记", copied.isUserEdited)
    }

    @Test
    fun existingCreationTimestampWinsOverAZeroFromTheSource() {
        val stampedTemplate = weekRow.copy(id = 56L, createdAt = 1_600_000_000_000L)

        val copied = RepeatWeeklyRules.copyIntoTemplate(
            source = weekRow.copy(createdAt = 0L),
            existing = stampedTemplate,
        )

        assertEquals(1_600_000_000_000L, copied.createdAt)
    }

    @Test
    fun blankTimestampOnBothSidesStaysBlank() {
        val copied = RepeatWeeklyRules.copyIntoTemplate(
            source = weekRow.copy(createdAt = 0L),
            existing = weekRow.copy(id = 56L, createdAt = 0L),
        )

        assertEquals(0L, copied.createdAt)
    }
}
