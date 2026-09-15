package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.LocalRuleAdvisor
import com.ironhabit.app.domain.model.AdoptResult
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseSource
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SuggestExercisesUseCase] 单测 —— 覆盖派工单的 **不变量 3（adopt 幂等）**。
 *
 * 幂等键 = `exercises.name`：**应用层"命中即跳过"优先**，数据库唯一约束只作兜底，
 * 不以抛异常来表达"已存在"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SuggestExercisesUseCaseTest {

    private val exerciseRepository: ExerciseRepository = mockk()
    private val settingsRepository: SettingsRepository = mockk()

    /** 模拟"动作库"：收入后真的出现在库里（第二次 adopt 就会命中）。 */
    private val libraryState: MutableStateFlow<List<Exercise>> = MutableStateFlow(emptyList())
    private var nextId: Long = 1L

    private lateinit var useCase: SuggestExercisesUseCase

    /** 装配默认桩：空档案 + 空库；`upsert` 会真的把动作写进 [libraryState]。 */
    private fun setUp(profile: UserProfile = UserProfile()) {
        coEvery { exerciseRepository.upsert(any()) } answers {
            val incoming: Exercise = firstArg()
            val id: Long = nextId++
            libraryState.value = libraryState.value + incoming.copy(id = id)
            id
        }
        every { exerciseRepository.observeActive() } returns libraryState
        every { exerciseRepository.observeInactive() } returns flowOf(emptyList())
        every { settingsRepository.profile() } returns flowOf(profile)
        useCase = SuggestExercisesUseCase(
            exerciseRepository = exerciseRepository,
            settingsRepository = settingsRepository,
            advisor = LocalRuleAdvisor,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    // ---------------- 不变量 3：adopt 幂等 ----------------

    @Test
    fun adoptSameNameTwice_isIdempotent() = runTest {
        setUp()

        val first = useCase.adopt("靠墙静蹲")
        val second = useCase.adopt("靠墙静蹲")

        assertEquals("第一次收入 → ADDED", AdoptResult.ADDED, first)
        assertEquals(
            "第二次收入同一 name → ALREADY_EXISTS（幂等命中，不产生重复行）",
            AdoptResult.ALREADY_EXISTS,
            second,
        )
        coVerify(exactly = 1) { exerciseRepository.upsert(any()) }
        assertEquals(
            "库里只应有一条同名动作",
            1,
            libraryState.value.count { it.name == "靠墙静蹲" },
        )
    }

    @Test
    fun adoptWritesWithAiSuggestedSource() = runTest {
        // 力量类候选需要"真的有器械"才会进入建议池（空器械集 = 仅自重）。
        setUp(profile = UserProfile(equipment = setOf(Equipment.DUMBBELL)))

        val result = useCase.adopt("弹力带面拉")

        assertEquals(AdoptResult.ADDED, result)
        val adopted: Exercise = libraryState.value.first { it.name == "弹力带面拉" }
        assertEquals(
            "收入的动作为 AI_SUGGESTED，与内置 / 自定义**同等可用**",
            ExerciseSource.AI_SUGGESTED,
            adopted.source,
        )
        assertTrue(adopted.isActive)
    }

    @Test
    fun adoptUnknownName_writesNothing() = runTest {
        setUp()

        val result = useCase.adopt("一个不存在的动作")

        assertEquals("非候选 → 不写入（幂等：宁可不写，也不写脏数据）", AdoptResult.ALREADY_EXISTS, result)
        coVerify(exactly = 0) { exerciseRepository.upsert(any()) }
    }

    @Test
    fun adoptBlankName_writesNothing() = runTest {
        setUp()

        val result = useCase.adopt("   ")

        assertEquals("空名字 → 不写入", AdoptResult.ALREADY_EXISTS, result)
        coVerify(exactly = 0) { exerciseRepository.upsert(any()) }
    }

    @Test
    fun suggest_returnsOnlyNotAdoptedCandidates() = runTest {
        setUp()

        val before = useCase.suggest()
        assertTrue("初始应有可收入的建议", before.suggestions.isNotEmpty())
        assertEquals("本地规则顾问（开关默认关）→ 来源标注为 LOCAL_RULES", AdviceSource.LOCAL_RULES, before.source)
        assertEquals("未发生回落 → fallbackReason 为 null", null, before.fallbackReason)

        val target: String = before.suggestions.first().name
        useCase.adopt(target)

        val after = useCase.suggest()
        assertFalse(
            "已收入的动作不得再从建议里出现（幂等：多次调用结果收敛）",
            after.suggestions.any { it.name == target },
        )
        assertEquals(before.suggestions.size - 1, after.suggestions.size)
    }

    @Test
    fun suggest_respectsInjuryAndEquipmentFilters() = runTest {
        // 膝伤 → 排除腿部候选；无器械（空集 → {NONE}）→ 排除力量候选。
        setUp(profile = UserProfile(injuryAreas = setOf(InjuryArea.KNEE)))

        val suggestions = useCase.suggest().suggestions

        assertFalse("膝伤 → 排除「靠墙静蹲」（腿部）", suggestions.any { it.name == "靠墙静蹲" })
        assertFalse("膝伤 → 排除「坐姿提踵」（腿部）", suggestions.any { it.name == "坐姿提踵" })
        assertFalse("无器械 → 排除「哑铃肩上推举」", suggestions.any { it.name == "哑铃肩上推举" })
        assertTrue("有氧候选应保留", suggestions.any { it.name == "椭圆机稳态" })
    }
}
