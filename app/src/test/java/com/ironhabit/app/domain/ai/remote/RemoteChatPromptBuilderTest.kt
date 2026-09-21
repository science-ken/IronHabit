package com.ironhabit.app.domain.ai.remote

import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.usecase.CoachContext
import com.ironhabit.app.domain.usecase.CoachPlanLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.ironhabit.app.domain.usecase.CoachTurn

/**
 * [RemoteChatPromptBuilder] 单测（子项 A · AI 自由问答）。
 *
 * 纯函数、零 IO、零网络 → 离线可跑。覆盖两件事：
 * 1. **提示词硬约束**：角色 / 拒答范围 / 不做医疗诊断 / 简体中文 / 200 字 / JSON 字段名；
 * 2. **载荷完整性**：用户问题与用户现状（档案 / 本周计划 / 近期打卡 / 今日饮食）都必须进 JSON；
 * 3. **回答解析**：JSON、围栏包裹、纯文本回退、缺 `answer` 字段（**绝不把 JSON 原文当回答**）、空白。
 */
class RemoteChatPromptBuilderTest {

    private fun context(): CoachContext = CoachContext(
        profile = UserProfile(
            gender = Gender.MALE,
            age = 32,
            heightCm = 178,
            goal = Goal.BULK,
        ),
        weeklyPlan = listOf(
            CoachPlanLine(dayOfWeek = 1, exerciseName = "深蹲", targetSets = 4, targetReps = 8),
        ),
        checkInCount = 5,
        windowDays = 7,
        averageRpe = 7.5,
        weightDeltaKg = 0.4f,
        currentStreak = 9,
        todayIntakeKcal = 1500,
        todayPlanKcal = 2300,
    )

    // ---------------- system 提示词 ----------------

    @Test
    fun systemPrompt_declaresCoachRoleAndJsonAnswerContract() {
        val prompt = RemoteChatPromptBuilder.buildChatSystemPrompt()

        assertTrue("应声明「私人健身教练」角色", prompt.contains("私人健身教练"))
        assertTrue("应限定回答范围并礼貌拒答", prompt.contains("拒答"))
        assertTrue(
            "应禁止医疗诊断并要求就医",
            prompt.contains("不做医疗诊断") && prompt.contains("咨询医生"),
        )
        assertTrue("应要求简体中文", prompt.contains("简体中文"))
        assertTrue("应限制在 200 字以内", prompt.contains("200 字"))
        assertTrue("应要求 JSON 且字段名为 answer", prompt.contains("{\"answer\""))
    }

    // ---------------- user 载荷 ----------------

    @Test
    fun userPrompt_carriesQuestionAndWholeContext() {
        val json = RemoteChatPromptBuilder.buildChatUserPrompt(
            question = "今天练完有点累，明天怎么调整？",
            context = context(),
        )

        assertTrue("问题必须进载荷", json.contains("\"question\":\"今天练完有点累，明天怎么调整？\""))
        assertTrue("档案性别", json.contains("\"gender\":\"MALE\""))
        assertTrue("档案年龄", json.contains("\"age\":32"))
        assertTrue("档案身高", json.contains("\"heightCm\":178"))
        assertTrue("档案目标", json.contains("\"goal\":\"BULK\""))
        assertTrue("本周计划动作名", json.contains("\"exerciseName\":\"深蹲\""))
        assertTrue("本周计划目标组数", json.contains("\"targetSets\":4"))
        assertTrue("近 N 天打卡条数", json.contains("\"count\":5"))
        assertTrue("统计窗口", json.contains("\"windowDays\":7"))
        assertTrue("平均 RPE", json.contains("\"averageRpe\":7.5"))
        assertTrue("体重变化", json.contains("\"weightDeltaKg\":0.4"))
        assertTrue("连续天数", json.contains("\"currentStreak\":9"))
        assertTrue("今日已摄入", json.contains("\"intakeKcal\":1500"))
        assertTrue("今日计划摄入", json.contains("\"planKcal\":2300"))
    }

    @Test
    fun userPrompt_withEmptyContext_stillProducesValidJsonWithNulls() {
        val json = RemoteChatPromptBuilder.buildChatUserPrompt(
            question = "晚饭怎么吃",
            context = CoachContext(),
        )

        assertTrue("空计划应是空数组", json.contains("\"weeklyPlan\":[]"))
        assertTrue("无 RPE 记录应是 null", json.contains("\"averageRpe\":null"))
        assertTrue("无体重对比应是 null", json.contains("\"weightDeltaKg\":null"))
    }

    /**
     * D2 的最后一环：ViewModel 把轮次交给 UseCase 之后，**必须真的序列化进载荷**。
     *
     * 少这一条的话，把 history 传进 `buildChatUserPrompt` 却忘了塞进 `ChatPayload`
     * 也能全绿 —— 而模型看到的还是只有一句孤零零的"那饮食呢"。
     */
    @Test
    fun chatUserPrompt_carriesCompletedTurnsAsHistory() {
        val json = RemoteChatPromptBuilder.buildChatUserPrompt(
            question = "那饮食呢",
            context = CoachContext(),
            history = listOf(
                CoachTurn(question = "要不要练腿", answer = "练，4 组 × 8 次"),
                CoachTurn(question = "几点练", answer = "下班后 30 分钟内"),
            ),
        )

        assertTrue("history 必须进载荷", json.contains("\"history\":["))
        assertTrue("上一轮的问题", json.contains("\"question\":\"要不要练腿\""))
        assertTrue("上一轮的回答", json.contains("\"answer\":\"练，4 组 × 8 次\""))
        assertTrue(
            "旧→新排：第一轮必须出现在第二轮之前",
            json.indexOf("要不要练腿") < json.indexOf("几点练"),
        )
    }

    /** 首轮没历史：`history` 是空数组，不是缺字段也不是 null（模型侧好判断）。 */
    @Test
    fun chatUserPrompt_firstTurnHasEmptyHistory() {
        val json = RemoteChatPromptBuilder.buildChatUserPrompt(
            question = "第一个问题",
            context = CoachContext(),
        )

        assertTrue(json.contains("\"history\":[]"))
    }

    // ---------------- 回答解析 ----------------

    @Test
    fun parseChatAnswer_readsAnswerField() {
        assertEquals(
            "多喝水",
            RemoteChatPromptBuilder.parseChatAnswer("""{"answer":"多喝水"}"""),
        )
    }

    @Test
    fun parseChatAnswer_stripsCodeFence() {
        val raw = "```json\n{\"answer\":\"先热身再上重量\"}\n```"

        assertEquals("先热身再上重量", RemoteChatPromptBuilder.parseChatAnswer(raw))
    }

    @Test
    fun parseChatAnswer_plainTextFallsBackToRaw() {
        // 模型偶尔不守 JSON 约束：非空正文照收，避免"明明有内容却报失败"。
        assertEquals("直接给的建议", RemoteChatPromptBuilder.parseChatAnswer("直接给的建议"))
    }

    @Test
    fun parseChatAnswer_jsonWithoutAnswerField_isTreatedAsInvalid() {
        // 合法 JSON 但没有 answer → 视为无效，**绝不把 JSON 原文当回答显示给用户**。
        assertNull(RemoteChatPromptBuilder.parseChatAnswer("""{"foo":1}"""))
        assertNull(RemoteChatPromptBuilder.parseChatAnswer("""{"answer":""}"""))
    }

    @Test
    fun parseChatAnswer_blankOrEmptyFence_returnsNull() {
        assertNull(RemoteChatPromptBuilder.parseChatAnswer(""))
        assertNull(RemoteChatPromptBuilder.parseChatAnswer("   "))
        assertNull(RemoteChatPromptBuilder.parseChatAnswer("```json\n\n```"))
    }

    @Test
    fun parseChatAnswer_trimsSurroundingWhitespace() {
        assertEquals(
            "答案是",
            RemoteChatPromptBuilder.parseChatAnswer("""{"answer":"  答案是  "}"""),
        )
    }

    // ---------------- 子项 B：饮食「为什么这样吃」 ----------------

    @Test
    fun dietSystemPrompt_forbidsRenumberingAndMedicalAdvice() {
        val prompt = RemoteChatPromptBuilder.buildDietSystemPrompt()

        assertTrue("必须禁止模型自己重算数值", prompt.contains("不要重新计算"))
        assertTrue("应禁止医疗诊断并要求就医", prompt.contains("咨询医生"))
        assertTrue("应限制在 200 字以内", prompt.contains("200 字"))
        assertTrue("应要求 JSON 且字段名为 answer", prompt.contains("{\"answer\""))
    }

    @Test
    fun dietUserPrompt_carriesLocalTargetAndDietNumbers() {
        val json = RemoteChatPromptBuilder.buildDietUserPrompt(
            context = context(),
            target = DietTarget(targetKcal = 2300, targetProtein = 150),
        )

        assertTrue("本地目标热量（模型不得改）", json.contains("\"targetKcal\":2300"))
        assertTrue("本地目标蛋白质（模型不得改）", json.contains("\"targetProtein\":150"))
        assertTrue("是否用了默认值", json.contains("\"usedDefaults\":false"))
        assertTrue("今日已摄入", json.contains("\"intakeKcal\":1500"))
        assertTrue("今日计划摄入", json.contains("\"planKcal\":2300"))
        assertTrue("档案目标", json.contains("\"goal\":\"BULK\""))
        assertTrue("近期打卡条数", json.contains("\"count\":5"))
    }

    @Test
    fun dietUserPrompt_marksUsedDefaultsWhenProfileIncomplete() {
        val json = RemoteChatPromptBuilder.buildDietUserPrompt(
            context = CoachContext(),
            target = DietTarget(targetKcal = 2000, targetProtein = 100, usedDefaults = true),
        )

        assertTrue(json.contains("\"usedDefaults\":true"))
    }

    // ---------------- 子项 C：进度解读 ----------------

    @Test
    fun insightSystemPrompt_forbidsChangingNumbers_andAsksForNextStep() {
        val prompt = RemoteChatPromptBuilder.buildInsightSystemPrompt()

        assertTrue("必须禁止模型改动数字", prompt.contains("不得改动"))
        assertTrue("应要求给下一步建议", prompt.contains("下一步建议"))
        assertTrue("应禁止医疗诊断并要求就医", prompt.contains("咨询医生"))
        assertTrue("应限制在 200 字以内", prompt.contains("200 字"))
        assertTrue("应要求 JSON 且字段名为 answer", prompt.contains("{\"answer\""))
    }

    @Test
    fun insightUserPrompt_carriesLocalStats() {
        val json = RemoteChatPromptBuilder.buildInsightUserPrompt(context())

        assertTrue("打卡条数", json.contains("\"count\":5"))
        assertTrue("统计窗口", json.contains("\"windowDays\":7"))
        assertTrue("平均 RPE", json.contains("\"averageRpe\":7.5"))
        assertTrue("连续天数", json.contains("\"currentStreak\":9"))
        assertTrue("体重变化", json.contains("\"weightDeltaKg\":0.4"))
        assertTrue("今日饮食", json.contains("\"intakeKcal\":1500"))
    }
}
