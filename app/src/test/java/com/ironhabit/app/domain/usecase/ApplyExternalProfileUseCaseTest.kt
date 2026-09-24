package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ProfileFieldDiff
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ApplyExternalProfileUseCase] 单测 —— 钉的是"只写勾了的那几项"。
 *
 * 档案没有整体 `saveProfile()`，是逐字段 setter；这条通道的安全边界全靠这一点：
 * 没勾的字段连读都不读，"导入顺手把身高改了"在类型上就不可表达。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApplyExternalProfileUseCaseTest {

    private val settings: SettingsRepository = mockk(relaxed = true)
    private val useCase = ApplyExternalProfileUseCase(settings)

    @Test
    fun apply_routesEachDiffToItsOwnSetter() = runTest {
        coEvery { settings.setProfileGoal(any()) } returns Unit

        val applied = useCase(
            listOf(
                ProfileFieldDiff.GoalChange(Goal.BULK, Goal.CUT),
                ProfileFieldDiff.WeightChange(80f, 74.5f),
                ProfileFieldDiff.DaysChange(3, 5),
                ProfileFieldDiff.EquipmentChange(emptySet(), setOf(Equipment.PULLUP_BAR)),
                ProfileFieldDiff.InjuryAreaChange(setOf(InjuryArea.KNEE), emptySet()),
                ProfileFieldDiff.InjuryNoteChange("旧备注", "新备注"),
            ),
        )

        assertEquals(6, applied)
        coVerify(exactly = 1) { settings.setProfileGoal(Goal.CUT) }
        coVerify(exactly = 1) { settings.setProfileGoalWeightKg(74.5f) }
        coVerify(exactly = 1) { settings.setProfileTrainingDaysPerWeek(5) }
        coVerify(exactly = 1) { settings.setProfileEquipment(setOf(Equipment.PULLUP_BAR)) }
        coVerify(exactly = 1) { settings.setProfileInjuryAreas(emptySet()) }
        coVerify(exactly = 1) { settings.setProfileInjuryNote("新备注") }
    }

    @Test
    fun apply_emptySelectionWritesNothing() = runTest {
        assertEquals(0, useCase(emptyList()))

        coVerify(exactly = 0) { settings.setProfileGoal(any()) }
        coVerify(exactly = 0) { settings.setProfileGoalWeightKg(any()) }
        coVerify(exactly = 0) { settings.setProfileTrainingDaysPerWeek(any()) }
        coVerify(exactly = 0) { settings.setProfileEquipment(any()) }
        coVerify(exactly = 0) { settings.setProfileInjuryAreas(any()) }
        coVerify(exactly = 0) { settings.setProfileInjuryNote(any()) }
    }

    @Test
    fun apply_clearedNote_goesThroughAsNull_notBlankString() = runTest {
        useCase(listOf(ProfileFieldDiff.InjuryNoteChange("原来有一句", "   ")))

        coVerify(exactly = 1) { settings.setProfileInjuryNote(null) }
    }

    @Test
    fun apply_onlyTheCheckedRows_areWritten() = runTest {
        // 用户只勾了目标：其余字段必须一个都不碰（哪怕文档里写了）。
        useCase(listOf(ProfileFieldDiff.GoalChange(Goal.BULK, Goal.RECOMP)))

        coVerify(exactly = 1) { settings.setProfileGoal(Goal.RECOMP) }
        coVerify(exactly = 0) { settings.setProfileTrainingDaysPerWeek(any()) }
        coVerify(exactly = 0) { settings.setProfileEquipment(any()) }
        coVerify(exactly = 0) { settings.setProfileInjuryAreas(any()) }
        coVerify(exactly = 0) { settings.setProfileGender(any()) }
        coVerify(exactly = 0) { settings.setProfileHeightCm(any()) }
        coVerify(exactly = 0) { settings.setProfileAge(any()) }
        coVerify(exactly = 0) { settings.setProfileBodyFatPct(any()) }
    }
}
