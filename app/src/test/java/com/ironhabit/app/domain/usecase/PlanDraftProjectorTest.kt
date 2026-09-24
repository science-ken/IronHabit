package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.PlanItemDraft
import com.ironhabit.app.domain.model.PlanProposal
import com.ironhabit.app.domain.model.PlanReason
import com.ironhabit.app.domain.model.PlannedDay
import com.ironhabit.app.domain.model.TrainingFocus
import com.ironhabit.app.domain.model.WeekPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlanDraftProjector] 的纯 JVM 单测 —— 四条不变量各一组。
 *
 * 这个投影器现在是**两条来源共用**的（内置生成 / 外部 AI 文档导入），所以它挡一次漏洞
 * 就是两边同时漏。尤其是"外部导入不许回收陈旧行"那一条：传错一个布尔，用户问回来的
 * 计划会在采纳那天之后把旧行停成空周。
 */
class PlanDraftProjectorTest {

    private val targetWeek: Long = 20724L

    private fun row(
        id: Long,
        day: Int,
        exerciseId: Long = 1L,
        isActive: Boolean = true,
        isUserEdited: Boolean = false,
        weekStart: Long = targetWeek,
    ) = WeekPlan(
        id = id,
        exerciseId = exerciseId,
        dayOfWeek = day,
        isActive = isActive,
        isUserEdited = isUserEdited,
        weekStartEpochDay = weekStart,
    )

    private fun item(exerciseId: Long, sets: Int = 3, reps: Int = 12) = PlanItemDraft(
        exerciseId = exerciseId,
        targetSets = sets,
        targetReps = reps,
        targetWeightKg = 60f,
        targetDurationMin = null,
        reason = PlanReason.PRIMARY_LIFT,
    )

    private fun proposalOf(vararg days: PlannedDay) = PlanProposal(days = days.toList())

    private fun day(dayOfWeek: Int, vararg items: PlanItemDraft) = PlannedDay(
        dayOfWeek = dayOfWeek,
        focus = TrainingFocus.FULL_BODY,
        items = items.toList(),
    )

    private fun project(
        proposal: PlanProposal,
        weekRows: List<WeekPlan>,
        templateEditedRows: List<WeekPlan> = emptyList(),
        retireStaleRows: Boolean = true,
    ): PlanPreview = PlanDraftProjector.project(
        proposal = proposal,
        targetWeek = targetWeek,
        weekRows = weekRows,
        templateEditedRows = templateEditedRows,
        retireStaleRows = retireStaleRows,
    )

    // ---------------- 不变量 3：一律落到目标周 ----------------

    @Test
    fun project_everyDraftCarriesTargetWeek_neverTheTemplate() {
        val preview = project(
            proposal = proposalOf(day(1, item(1L), item(2L)), day(3, item(1L))),
            weekRows = emptyList(),
        )

        val rows = preview.draftsByDay.values.flatten()
        assertEquals(3, rows.size)
        assertTrue(
            "少带这一维就会落到 0 =「每周相同」，于是「给下周排课」会偷偷改掉每周循环那份",
            rows.all { it.weekStartEpochDay == targetWeek },
        )
        assertEquals(targetWeek, preview.weekStartEpochDay)
    }

    @Test
    fun project_sortOrderIsPerDaySequence_notWholeWeek() {
        val preview = project(
            proposal = proposalOf(day(1, item(1L), item(2L)), day(3, item(3L))),
            weekRows = emptyList(),
        )

        assertEquals(listOf(0, 1), preview.draftsByDay.getValue(1).map { it.sortOrder })
        assertEquals(
            "周三那条必须从 0 重新数，否则界面上今天第一条排在别行动作后面",
            listOf(0),
            preview.draftsByDay.getValue(3).map { it.sortOrder },
        )
    }

    @Test
    fun project_numberAndDurationFieldsSurvive() {
        val preview = project(
            proposal = proposalOf(day(1, item(1L, sets = 5, reps = 8))),
            weekRows = emptyList(),
        )

        val row = preview.draftsByDay.getValue(1).single()
        assertEquals(5, row.targetSets)
        assertEquals(8, row.targetReps)
        assertEquals(60f, row.targetWeightKg!!)
    }

    // ---------------- 不变量 1：手改槽位（含软删行）一条都不写 ----------------

    @Test
    fun project_userEditedSlot_isNeitherOverwrittenNorRevived() {
        val editedActive = row(id = 11L, day = 1, exerciseId = 1L, isUserEdited = true)
        val editedSoftDeleted = row(id = 12L, day = 3, exerciseId = 2L, isActive = false, isUserEdited = true)

        val preview = project(
            proposal = proposalOf(day(1, item(1L)), day(3, item(2L), item(3L))),
            weekRows = listOf(editedActive, editedSoftDeleted),
        )

        assertEquals(
            "软删的手改行同样不能被「复活」 —— 只留没被挡的那一条",
            listOf(3L),
            preview.draftsByDay.getValue(3).map { it.exerciseId },
        )
        assertTrue(
            "周一整天的草案都被手改槽位挡住 → 该天没有可写草案",
            preview.draftsByDay[1].isNullOrEmpty(),
        )
    }

    // ---------------- 不变量 2：模板负责的整天交回模板 ----------------

    @Test
    fun project_dayOwnedByEditedTemplate_isLeftToTemplateEntirely() {
        // 周四在「每周相同」里被手改过，且本周周四没有启用专属行 → 本周生效计划来自模板。
        // 这时哪怕只写一条专属行，周四都不再回落模板，用户删掉的动作会回来。
        val templateEdited = row(id = 21L, day = 4, exerciseId = 5L, isUserEdited = true, weekStart = 0L)

        val preview = project(
            proposal = proposalOf(day(4, item(5L), item(6L)), day(5, item(6L))),
            weekRows = listOf(row(id = 31L, day = 5, exerciseId = 9L)),
            templateEditedRows = listOf(templateEdited),
        )

        assertTrue("周四整日不写", preview.draftsByDay[4].isNullOrEmpty())
        assertEquals(setOf(4), preview.templateOwnedDays)
        assertEquals(listOf(6L), preview.draftsByDay.getValue(5).map { it.exerciseId })
    }

    @Test
    fun project_templateEditedDayWithActiveWeekRows_isNotTemplateOwned() {
        // 本周周四已经有启用专属行 → 生效计划本来就是本周的，模板手改不再接管这一天。
        val preview = project(
            proposal = proposalOf(day(4, item(7L))),
            weekRows = listOf(row(id = 41L, day = 4, exerciseId = 1L)),
            templateEditedRows = listOf(row(id = 21L, day = 4, exerciseId = 5L, isUserEdited = true, weekStart = 0L)),
        )

        assertEquals(setOf<Int>(), preview.templateOwnedDays)
        assertEquals(listOf(7L), preview.draftsByDay.getValue(4).map { it.exerciseId })
    }

    // ---------------- 不变量 4：回收范围 —— 外部导入必须关掉 ----------------

    @Test
    fun project_retirementRowsFollowTheSourceSemantics() {
        val weekRows = listOf(row(id = 51L, day = 2, exerciseId = 8L))

        val generated = project(proposalOf(day(2, item(8L))), weekRows, retireStaleRows = true)
        val imported = project(proposalOf(day(2, item(8L))), weekRows, retireStaleRows = false)

        assertEquals("内置生成要把本周现有行留给 commit 做陈旧行回收", weekRows, generated.weekRowsForRetirement)
        assertTrue(
            "外部导入的文档常常只写几天，「没列出来」不构成意见 → 不给它任何可回收的行",
            imported.weekRowsForRetirement.isEmpty(),
        )
    }

    @Test
    fun project_templateRowsNeverEnterRetirement() {
        val preview = project(
            proposal = proposalOf(day(1, item(1L))),
            weekRows = listOf(row(id = 61L, day = 1, exerciseId = 1L)),
            templateEditedRows = listOf(row(id = 62L, day = 2, exerciseId = 2L, isUserEdited = true, weekStart = 0L)),
        )

        assertTrue(
            "把模板行并进回收会把整份「每周相同」停用",
            preview.weekRowsForRetirement.none { it.weekStartEpochDay == WeekPlan.TEMPLATE_WEEK_START },
        )
    }

    // ---------------- 「已保留 N 条」的口径 ----------------

    @Test
    fun project_preservedCountOnlyCountsRowsTheUserCanSeeThisWeek() {
        val visible = row(id = 71L, day = 1, exerciseId = 1L, isUserEdited = true)
        val softDeleted = row(id = 72L, day = 2, exerciseId = 2L, isActive = false, isUserEdited = true)
        val template = row(id = 73L, day = 3, exerciseId = 3L, isUserEdited = true, weekStart = 0L)
        val proposal = proposalOf(day(6, item(6L))).copy(
            preservedUserEditedIds = listOf(71L, 72L, 73L),
        )

        val preview = project(
            proposal = proposal,
            weekRows = listOf(visible, softDeleted),
            templateEditedRows = listOf(template),
        )

        assertEquals(
            "报 3 条等于「提示说保留了 4 条，用户一条都找不到」 —— 模板行与软删行同样受保护，只是看不见",
            1,
            preview.preservedCount,
        )
    }

    @Test
    fun project_proposalMetadataIsPassedThroughUntouched() {
        val proposal = PlanProposal(
            days = listOf(day(1, item(1L))),
            analysis = "本周加重。",
            source = AdviceSource.EXTERNAL_AI_IMPORT,
        )

        val preview = project(proposal, weekRows = emptyList())

        assertEquals(AdviceSource.EXTERNAL_AI_IMPORT, preview.source)
        assertEquals("本周加重。", preview.analysis)
        assertEquals(proposal.notes, preview.notes)
    }
}
