package com.ironhabit.app.domain.ai.external

import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExternalProfilePatch.diffsAgainst] 的纯 JVM 单测。
 *
 * 这个函数决定"用户在预览页会看到哪几行可勾"，所以两条最容易出错的方向都要钉住：
 * - **没变的不许出现**（勾一个"目标：增肌 → 增肌"是噪音，还会让人怀疑整页的可信度）；
 * - **越界值必须先钳再比**（钳制和落库用同一个 `ProfileLimits`，比较口径不可能和写入分叉）。
 */
class ExternalProfilePatchTest {

    private val current = UserProfile(
        goal = Goal.BULK,
        goalWeightKg = 80f,
        trainingDaysPerWeek = 3,
        equipment = setOf(Equipment.BARBELL, Equipment.DUMBBELL),
        injuryAreas = setOf(InjuryArea.KNEE),
        injuryNote = "深蹲到底右膝有点顶",
    )

    @Test
    fun diffs_emptyPatch_producesNothing() {
        assertTrue(ExternalProfilePatch().diffsAgainst(current).isEmpty())
    }

    @Test
    fun diffs_valuesIdenticalToCurrent_produceNoRows() {
        val patch = ExternalProfilePatch(
            goal = Goal.BULK,
            goalWeightKg = 80f,
            trainingDaysPerWeek = 3,
            // 集合按内容比，不按顺序比
            equipment = setOf(Equipment.DUMBBELL, Equipment.BARBELL),
            injuryAreas = setOf(InjuryArea.KNEE),
            injuryNote = "深蹲到底右膝有点顶",
        )

        assertEquals(
            "每一项都和现在一样 → 一行都不该出现",
            emptyList<ProfileFieldDiff>(),
            patch.diffsAgainst(current),
        )
    }

    @Test
    fun diffs_outOfDomainDays_areClampedBeforeComparing() {
        // 99 钳到 6：当前已经是 6 的话，这一项根本不该出现（写它等于没写）。
        val alreadySix = current.copy(trainingDaysPerWeek = 6)
        assertTrue(
            ExternalProfilePatch(trainingDaysPerWeek = 99).diffsAgainst(alreadySix).isEmpty(),
        )

        val diffs = ExternalProfilePatch(trainingDaysPerWeek = 99).diffsAgainst(current)

        assertEquals(listOf(ProfileFieldDiff.DaysChange(from = 3, to = 6)), diffs)
    }

    @Test
    fun diffs_outOfDomainWeight_isClampedNotRejected() {
        val diffs = ExternalProfilePatch(goalWeightKg = 400f).diffsAgainst(current)

        // 300 = ProfileLimits 上界：钳到能存的最大值，而不是丢掉或原样写进去。
        assertEquals(
            listOf(ProfileFieldDiff.WeightChange(from = 80f, to = 300f)),
            diffs,
        )
    }

    @Test
    fun diffs_missingCurrentWeight_showsOldValueAsEmpty() {
        val noWeight = current.copy(goalWeightKg = null)

        val diffs = ExternalProfilePatch(goalWeightKg = 75f).diffsAgainst(noWeight)

        assertEquals(listOf(ProfileFieldDiff.WeightChange(from = null, to = 75f)), diffs)
    }

    @Test
    fun diffs_emptyEquipment_isADeliberateClear_notAnIgnoredField() {
        val diffs = ExternalProfilePatch(equipment = emptySet()).diffsAgainst(current)

        assertEquals(
            listOf(ProfileFieldDiff.EquipmentChange(from = current.equipment, to = emptySet())),
            diffs,
        )
    }

    @Test
    fun diffs_overLongInjuryNote_isTruncatedThenCompared() {
        val long = "顶".repeat(260)

        val diffs = ExternalProfilePatch(injuryNote = long).diffsAgainst(current)

        val change = diffs.single() as ProfileFieldDiff.InjuryNoteChange
        assertEquals(200, change.to.length)
    }

    @Test
    fun diffs_blankNoteAgainstAbsentNote_isNotAChange() {
        // 当前没写备注（null）而文档给了空串：两者在档案里是同一件事，不该占一行。
        val noNote = current.copy(injuryNote = null)

        assertTrue(ExternalProfilePatch(injuryNote = "").diffsAgainst(noNote).isEmpty())
    }

    @Test
    fun diffs_rowsKeepTheOrderTheFieldsAreDeclaredIn() {
        val patch = ExternalProfilePatch(
            injuryAreas = setOf(InjuryArea.SHOULDER),
            goal = Goal.CUT,
            trainingDaysPerWeek = 5,
        )

        val diffs = patch.diffsAgainst(current)

        assertEquals(
            "顺序固定，界面按序渲染、按 index 记账才不会错位",
            listOf(
                ProfileFieldDiff.GoalChange(from = Goal.BULK, to = Goal.CUT),
                ProfileFieldDiff.DaysChange(from = 3, to = 5),
                ProfileFieldDiff.InjuryAreaChange(from = setOf(InjuryArea.KNEE), to = setOf(InjuryArea.SHOULDER)),
            ),
            diffs,
        )
    }
}
