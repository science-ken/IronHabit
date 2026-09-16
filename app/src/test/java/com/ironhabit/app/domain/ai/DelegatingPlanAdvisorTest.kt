package com.ironhabit.app.domain.ai

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.PlanProposal
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DelegatingPlanAdvisor] 单测 —— 覆盖派工单的**路由四态**：
 * ① 开关关 → 本地（不碰网络）；② 开关开但无 Key → 本地；
 * ③ 开关开 + 有 Key + 远端成功 → 远端；④ 远端任何异常 → 回落本地并记录原因。
 *
 * 本地 / 远端都是可编程 fake（不发网络、不碰真 Keystore）。
 */
class DelegatingPlanAdvisorTest {

    // ---------------- 测试夹具 ----------------

    /** 可编程 fake 顾问：记录是否被调用，可固定返回值或抛异常。 */
    private class FakeAdvisor(
        override val source: AdviceSource,
        private val result: () -> Any,
    ) : PlanAdvisor {
        var planWeekCalls: Int = 0
            private set
        var suggestCalls: Int = 0
            private set

        override fun planWeek(
            profile: UserProfile,
            library: List<Exercise>,
            existing: List<WeekPlan>,
            history: List<com.ironhabit.app.domain.model.ExerciseProgress>,
            today: LocalDate,
            bodyWeightKg: Float?,
        ): PlanProposal {
            planWeekCalls++
            @Suppress("UNCHECKED_CAST")
            return result() as PlanProposal
        }

        override fun suggestExercises(
            profile: UserProfile,
            candidates: List<Exercise>,
            existing: List<Exercise>,
        ): List<com.ironhabit.app.domain.model.ExerciseSuggestion> {
            suggestCalls++
            @Suppress("UNCHECKED_CAST")
            return result() as List<com.ironhabit.app.domain.model.ExerciseSuggestion>
        }
    }

    private val today: LocalDate = LocalDate(2026, 9, 14)
    private val localProposal: PlanProposal = PlanProposal(source = AdviceSource.LOCAL_RULES)
    private val remoteProposal: PlanProposal = PlanProposal(source = AdviceSource.REMOTE_LLM)

    private fun settings(remoteEnabled: Boolean): SettingsRepository = mockk {
        every { aiRemoteEnabled() } returns kotlinx.coroutines.flow.flowOf(remoteEnabled)
    }

    private fun credentials(configured: Boolean): AiCredentialsStore = mockk {
        every { isConfigured() } returns configured
        every { apiKey() } returns if (configured) "sk-test" else null
    }

    private fun delegating(
        remoteEnabled: Boolean,
        keyConfigured: Boolean,
        local: FakeAdvisor,
        remote: FakeAdvisor,
    ): DelegatingPlanAdvisor = DelegatingPlanAdvisor(
        local = local,
        remote = remote,
        credentials = credentials(keyConfigured),
        settingsRepository = settings(remoteEnabled),
    )

    // ---------------- ① 开关关（默认）→ 直接本地 ----------------

    @Test
    fun remoteDisabled_goesStraightToLocal_withoutTouchingRemote() {
        val local = FakeAdvisor(AdviceSource.LOCAL_RULES) { localProposal }
        val remote = FakeAdvisor(AdviceSource.REMOTE_LLM) { remoteProposal }
        val delegating = delegating(remoteEnabled = false, keyConfigured = true, local, remote)

        val proposal = delegating.planWeek(
            UserProfile(), emptyList(), emptyList(), emptyList(), today,
        )

        assertEquals(localProposal, proposal)
        assertEquals(1, local.planWeekCalls)
        assertEquals("开关关 → 远端一次都不该被调", 0, remote.planWeekCalls)
        assertEquals(AdviceSource.LOCAL_RULES, delegating.source)
        assertEquals(RemoteFallbackReason.REMOTE_DISABLED, delegating.lastFallbackReason)
    }

    // ---------------- ② 开关开但无 Key → 本地 ----------------

    @Test
    fun keyNotConfigured_fallsBackToLocal() {
        val local = FakeAdvisor(AdviceSource.LOCAL_RULES) { localProposal }
        val remote = FakeAdvisor(AdviceSource.REMOTE_LLM) { remoteProposal }
        val delegating = delegating(remoteEnabled = true, keyConfigured = false, local, remote)

        val proposal = delegating.planWeek(
            UserProfile(), emptyList(), emptyList(), emptyList(), today,
        )

        assertEquals(localProposal, proposal)
        assertEquals(0, remote.planWeekCalls)
        assertEquals(RemoteFallbackReason.KEY_NOT_CONFIGURED, delegating.lastFallbackReason)
    }

    // ---------------- ③ 开关开 + 有 Key + 远端成功 → 远端 ----------------

    @Test
    fun remoteSuccess_returnsRemoteResult_andRecordsRemoteSource() {
        val local = FakeAdvisor(AdviceSource.LOCAL_RULES) { localProposal }
        val remote = FakeAdvisor(AdviceSource.REMOTE_LLM) { remoteProposal }
        val delegating = delegating(remoteEnabled = true, keyConfigured = true, local, remote)

        val proposal = delegating.planWeek(
            UserProfile(), emptyList(), emptyList(), emptyList(), today,
        )

        assertEquals("远端成功 → 原样返回远端草案", remoteProposal, proposal)
        assertEquals(1, remote.planWeekCalls)
        assertEquals("成功路径不得触碰本地（结果不能被静默替换）", 0, local.planWeekCalls)
        assertEquals(AdviceSource.REMOTE_LLM, delegating.source)
        assertNull("成功 → 无回落原因", delegating.lastFallbackReason)
    }

    // ---------------- ④ 远端失败 → 回落本地并记录原因 ----------------

    @Test
    fun remoteFailure_fallsBackToLocal_andRecordsRemoteError() {
        val local = FakeAdvisor(AdviceSource.LOCAL_RULES) { localProposal }
        val remote = FakeAdvisor(AdviceSource.REMOTE_LLM) {
            throw RuntimeException("DeepSeek HTTP 503")   // 任意异常（网络 / 超时 / 解析），一视同仁
        }
        val delegating = delegating(remoteEnabled = true, keyConfigured = true, local, remote)

        val proposal = delegating.planWeek(
            UserProfile(), emptyList(), emptyList(), emptyList(), today,
        )

        assertEquals("失败回落 → 返回本地草案", localProposal, proposal)
        assertEquals(1, remote.planWeekCalls)
        assertEquals(1, local.planWeekCalls)
        assertEquals("回落时来源必须标回 LOCAL_RULES（诚实原则）", AdviceSource.LOCAL_RULES, delegating.source)
        assertEquals(RemoteFallbackReason.REMOTE_ERROR, delegating.lastFallbackReason)
    }

    // ---------------- suggestExercises 走同一路由 ----------------

    @Test
    fun suggestExercises_routesThroughTheSameDelegation() {
        val emptySuggestions: List<com.ironhabit.app.domain.model.ExerciseSuggestion> = emptyList()
        val local = FakeAdvisor(AdviceSource.LOCAL_RULES) { emptySuggestions }
        val remote = FakeAdvisor(AdviceSource.REMOTE_LLM) {
            throw RuntimeException("timeout")
        }
        val delegating = delegating(remoteEnabled = true, keyConfigured = true, local, remote)

        val suggestions = delegating.suggestExercises(UserProfile(), emptyList(), emptyList())

        assertTrue(suggestions.isEmpty())
        assertEquals(1, remote.suggestCalls)
        assertEquals(1, local.suggestCalls)
        assertEquals(RemoteFallbackReason.REMOTE_ERROR, delegating.lastFallbackReason)

        // 开关关时 suggest 也不该碰远端。
        val strictLocal = FakeAdvisor(AdviceSource.LOCAL_RULES) { emptySuggestions }
        val strictRemote = FakeAdvisor(AdviceSource.REMOTE_LLM) { emptySuggestions }
        val disabled = delegating(remoteEnabled = false, keyConfigured = true, strictLocal, strictRemote)
        disabled.suggestExercises(UserProfile(), emptyList(), emptyList())
        assertEquals(0, strictRemote.suggestCalls)
        assertEquals(
            "开关关的回落原因 = REMOTE_DISABLED（UI 据此只标注来源、不提示联网失败）",
            RemoteFallbackReason.REMOTE_DISABLED,
            disabled.lastFallbackReason,
        )
    }
}
