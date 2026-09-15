package com.ironhabit.app.domain.ai.remote

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.PlanReason
import com.ironhabit.app.domain.model.SuggestionReason
import com.ironhabit.app.domain.model.WeekPlan
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RemoteLlmAdvisor] 纯 JVM 单测 —— **全程零网络**：
 * - 解析走独立纯函数 [parseProposalJson] / [parseSuggestionsJson]；
 * - HTTP 走 fake [DeepSeekApi]（lambda 注入）；
 * - Key 走 mockk 的 [AiCredentialsStore]。
 *
 * 覆盖派工单三道防线：① 幻觉 exerciseId 丢弃 ② 畸形 JSON 抛可识别异常 ③ 越界值 coerce。
 */
class RemoteLlmAdvisorTest {

    private val credentials: AiCredentialsStore = mockk {
        every { apiKey() } returns FAKE_KEY
        every { isConfigured() } returns true
    }

    /** 可编程的 fake HTTP 客户端：返回预设文本 / 抛预设异常，并记录收到的提示词。 */
    private class FakeApi(
        private val respond: (system: String, user: String, key: String) -> String,
    ) : DeepSeekApi {
        var lastSystem: String? = null
            private set
        var lastUser: String? = null
            private set
        var lastKey: String? = null
            private set

        override fun complete(systemPrompt: String, userPrompt: String, apiKey: String): String {
            lastSystem = systemPrompt
            lastUser = userPrompt
            lastKey = apiKey
            return respond(systemPrompt, userPrompt, apiKey)
        }
    }

    // ---------------- 测试夹具 ----------------

    private fun exercise(id: Long, name: String = "ex-$id") = Exercise(
        id = id,
        name = name,
        category = ExerciseCategory.BODYWEIGHT,
        muscleGroups = listOf("核心"),
        isActive = true,
        defaultSets = 3,
        defaultReps = 12,
    )

    private val library: List<Exercise> = listOf(exercise(1L), exercise(2L), exercise(3L))

    // ---------------- 防线 ①：幻觉 exerciseId / 手改槽位 ----------------

    @Test
    fun parseProposal_unknownExerciseId_isDropped() {
        val json = """
            {"days":[{"dayOfWeek":1,"focus":"FULL_BODY","items":[
                {"exerciseId":1,"targetSets":3,"targetReps":12,"targetWeightKg":null},
                {"exerciseId":999,"targetSets":3,"targetReps":12,"targetWeightKg":null}
            ]}]}
        """.trimIndent()

        val proposal = parseProposalJson(json, library, existing = emptyList())

        val ids = proposal.days.single().items.map { it.exerciseId }
        assertTrue(
            "不在动作库里的 id（幻觉）必须整条丢弃，不崩、不写入",
            999L !in ids,
        )
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun parseProposal_userEditedSlot_isNeverGenerated() {
        val edited = WeekPlan(id = 100L, exerciseId = 1L, dayOfWeek = 1, isUserEdited = true)
        val json = """
            {"days":[{"dayOfWeek":1,"focus":"FULL_BODY","items":[
                {"exerciseId":1,"targetSets":3,"targetReps":12,"targetWeightKg":null},
                {"exerciseId":2,"targetSets":3,"targetReps":12,"targetWeightKg":null}
            ]}]}
        """.trimIndent()

        val proposal = parseProposalJson(json, library, existing = listOf(edited))

        assertEquals(
            "手改行计入「已保留」（与本地规则层同口径，含其 id）",
            listOf(100L),
            proposal.preservedUserEditedIds,
        )
        val ids = proposal.days.single().items.map { it.exerciseId }
        assertTrue("手改槽位（周一 × 动作1）不得生成条目", 1L !in ids)
        assertEquals(listOf(2L), ids)
    }

    // ---------------- 防线 ②：畸形 JSON → 可识别异常 ----------------

    @Test
    fun parseProposal_malformedJson_throwsRemoteAdvisorException() {
        val thrown = try {
            parseProposalJson("这不是 JSON", library, emptyList())
            null
        } catch (e: RemoteAdvisorException) {
            e
        }
        assertTrue("畸形输入必须抛 RemoteAdvisorException（委托层据此回落本地）", thrown != null)
    }

    @Test
    fun parseProposal_missingDaysField_throwsRemoteAdvisorException() {
        val thrown = try {
            parseProposalJson("""{"plan":"完全不对的结构"}""", library, emptyList())
            null
        } catch (e: RemoteAdvisorException) {
            e
        }
        assertTrue("缺 days 字段 → 结构畸形 → 可识别异常", thrown != null)
    }

    // ---------------- 防线 ③：越界值 coerce ----------------

    @Test
    fun parseProposal_outOfRangeSetsAndReps_areCoerced() {
        val json = """
            {"days":[{"dayOfWeek":1,"focus":"FULL_BODY","items":[
                {"exerciseId":1,"targetSets":0,"targetReps":-5,"targetWeightKg":null},
                {"exerciseId":2,"targetSets":999,"targetReps":10000,"targetWeightKg":-3.0}
            ]}]}
        """.trimIndent()

        val proposal = parseProposalJson(json, library, existing = emptyList())
        val items = proposal.days.single().items

        assertEquals("组数下界 coerce 到 1", 1, items[0].targetSets)
        assertEquals("次数下界 coerce 到 1", 1, items[0].targetReps)
        // 上界 31 = CheckIn.MAX_SETS（逐组打卡位图是 Int，第 32 组无法表示）；
        // 与 InputLimits.MAX_SETS / 提示词里的 1..31 保持同一口径。
        assertEquals("组数上界 coerce 到 31", 31, items[1].targetSets)
        assertEquals("次数上界 coerce 到 100", 100, items[1].targetReps)
        assertNull("非正重量视为自重（null）", items[1].targetWeightKg)
    }

    @Test
    fun parseProposal_invalidDayAndFocus_areClampedToSafeDefaults() {
        val json = """
            {"days":[{"dayOfWeek":9,"focus":"不存在的重点","items":[
                {"exerciseId":1,"targetSets":3,"targetReps":12,"targetWeightKg":null}
            ]}]}
        """.trimIndent()

        val proposal = parseProposalJson(json, library, existing = emptyList())
        val day = proposal.days.single()

        assertEquals("非法 dayOfWeek clamp 进 1..7", true, day.dayOfWeek in 1..7)
        assertEquals("未知 focus 回落 FULL_BODY，不崩", "FULL_BODY", day.focus.name)
    }

    // ---------------- 端到端（fake HTTP）与建议防线 ----------------

    @Test
    fun planWeek_viaFakeApi_buildsPromptAndParsesResponse() {
        val api = FakeApi { _, _, _ ->
            """{"days":[{"dayOfWeek":1,"focus":"FULL_BODY","items":[
                {"exerciseId":2,"targetSets":4,"targetReps":10,"targetWeightKg":20.0}]}]}"""
        }
        val advisor = RemoteLlmAdvisor(api, credentials)

        val proposal = advisor.planWeek(
            profile = com.ironhabit.app.domain.model.UserProfile(),
            library = library,
            existing = emptyList(),
            history = emptyList(),
            today = kotlinx.datetime.LocalDate(2026, 9, 14),
        )

        assertEquals(AdviceSource.REMOTE_LLM, proposal.source)
        assertEquals(2L, proposal.days.single().items.single().exerciseId)
        assertTrue(
            "user 提示词必须包含动作库 id 白名单（防幻觉的硬要求要喂给模型）",
            api.lastUser!!.contains("\"id\":2"),
        )
        assertEquals("Key 只进 HTTP header 参数，不落提示词", FAKE_KEY, api.lastKey)
    }

    @Test
    fun planWeek_apiFailure_throwsIdentifiableException() {
        val api = FakeApi { _, _, _ -> throw IOException("DeepSeek HTTP 503") }
        val advisor = RemoteLlmAdvisor(api, credentials)

        val thrown = try {
            advisor.planWeek(
                profile = com.ironhabit.app.domain.model.UserProfile(),
                library = library,
                existing = emptyList(),
                history = emptyList(),
                today = kotlinx.datetime.LocalDate(2026, 9, 14),
            )
            null
        } catch (e: RemoteAdvisorException) {
            e
        }
        assertTrue("HTTP 失败也必须以 RemoteAdvisorException 呈现（回落判据唯一）", thrown != null)
    }

    @Test
    fun parseSuggestions_namesOutsideCandidatePool_areDropped() {
        val candidates = listOf(exercise(1L, "鸟狗式"), exercise(2L, "死虫式"))
        val existing = listOf(exercise(50L, "死虫式"))
        val json = """{"suggestions":[
            {"name":"鸟狗式","reason":"GOAL_SUPPORT"},
            {"name":"模型编造的动作","reason":"GOAL_SUPPORT"},
            {"name":"死虫式","reason":"GOAL_SUPPORT"}
        ]}"""

        val suggestions = parseSuggestionsJson(json, candidates, existing)

        assertEquals(
            "候选池之外的名字丢弃 + 已在库里的名字丢弃（幂等）",
            listOf("鸟狗式"),
            suggestions.map { it.name },
        )
        assertEquals(SuggestionReason.GOAL_SUPPORT, suggestions.single().reason)
        assertTrue(
            "noteKey 必须是 strings.xml 资源名（不得内联中文）",
            suggestions.single().noteKey.matches(Regex("[a-z0-9_]+")),
        )
    }

    @Test
    fun parseSuggestions_unknownReason_fallsBackToGoalSupport() {
        val candidates = listOf(exercise(1L, "鸟狗式"))
        val json = """{"suggestions":[{"name":"鸟狗式","reason":" totally_made_up "}]}"""

        val suggestions = parseSuggestionsJson(json, candidates, existing = emptyList())

        assertEquals("未知 reason 不猜 → GOAL_SUPPORT", SuggestionReason.GOAL_SUPPORT, suggestions.single().reason)
    }

    @Test
    fun parseProposal_validPlanAssignsPositionReasons() {
        val json = """
            {"days":[{"dayOfWeek":3,"focus":"CARDIO_CORE","items":[
                {"exerciseId":1,"targetSets":3,"targetReps":12,"targetWeightKg":null},
                {"exerciseId":2,"targetSets":3,"targetReps":12,"targetWeightKg":null}
            ]}]}
        """.trimIndent()

        val proposal = parseProposalJson(json, library, existing = emptyList())
        val reasons = proposal.days.single().items.map { it.reason }

        assertEquals(listOf(PlanReason.PRIMARY_LIFT, PlanReason.SUPPLEMENT), reasons)
    }

    @Test
    fun parseProposal_withAnalysisField_mapsAnalysisAndEmptyBasis() {
        val json = """
            {"analysis":"结合你的增肌目标，本周安排周一全身、周三下肢、周五上肢推。","days":[{"dayOfWeek":1,"focus":"FULL_BODY","items":[
                {"exerciseId":2,"targetSets":3,"targetReps":12,"targetWeightKg":null}
            ]}]}
        """.trimIndent()

        val proposal = parseProposalJson(json, library, existing = emptyList())

        assertEquals(
            "远端返回的 analysis 应透传到 PlanProposal",
            "结合你的增肌目标，本周安排周一全身、周三下肢、周五上肢推。",
            proposal.analysis,
        )
        assertTrue("远端解析的 basis 恒为空列表", proposal.basis.isEmpty())
        assertEquals(AdviceSource.REMOTE_LLM, proposal.source)
    }

    private companion object {
        const val FAKE_KEY: String = "sk-test-000000000000"
    }
}
