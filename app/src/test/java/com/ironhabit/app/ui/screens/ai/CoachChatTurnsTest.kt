package com.ironhabit.app.ui.screens.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「问教练」历史的**成对轮次**口径（D2）。
 *
 * 以前按条截（`takeLast(6)`）：砍在一轮中间时，列表和发给模型的历史第一条
 * 会是上一轮的**回答**——半轮对话没有上文可接，"那饮食呢"就只能被泛泛回答。
 * 这里把两条规则钉死：
 * 1. 内存气泡永远不以半轮（非 USER）开头；
 * 2. 发给模型的只有"问了且模型真答了"的完整轮次，未联网 / 失败那种 UI 状态气泡
 *    **绝不**当历史发过去（那等于谎称模型说过那句话）。
 */
class CoachChatTurnsTest {

    private fun user(text: String) = CoachChatMessage(CoachChatKind.USER, text)

    private fun answer(text: String) = CoachChatMessage(CoachChatKind.ANSWER, text)

    private val noNetwork = CoachChatMessage(CoachChatKind.NEEDS_NETWORK)

    private val failed = CoachChatMessage(CoachChatKind.FAILED)

    // ---------------- appendChat：列表不以半轮开头 ----------------

    @Test
    fun appendChat_neverStartsOnHalfATurn() {
        var messages = emptyList<CoachChatMessage>()
        repeat(5) { index ->
            messages = messages.appendChat(user("问题$index"))
            messages = messages.appendChat(answer("回答$index"))
            // 每次都要能一眼看出这是"一轮完整的开头"，不是被砍剩的半轮
            assertEquals("第 $index 轮之后首条必须是提问", CoachChatKind.USER, messages.first().kind)
        }
        assertEquals("只留最近 3 轮 = 6 条", 6, messages.size)
    }

    @Test
    fun appendChat_keepsWholeRoundsOnly() {
        var messages = emptyList<CoachChatMessage>()
        repeat(5) { index ->
            messages = messages.appendChat(user("q$index")).appendChat(answer("a$index"))
        }

        // 保留的必须是完整的 (问, 答) 交替，不能出现两条连续的问或答
        assertEquals(
            listOf(CoachChatKind.USER, CoachChatKind.ANSWER, CoachChatKind.USER, CoachChatKind.ANSWER, CoachChatKind.USER, CoachChatKind.ANSWER),
            messages.map { message -> message.kind },
        )
        assertEquals(
            "丢的是最早的两轮，留下的是最近 3 轮",
            listOf("q2", "q3", "q4"),
            messages.filter { it.kind == CoachChatKind.USER }.map { message -> message.text },
        )
    }

    // ---------------- toCoachTurns：只发真问答 ----------------

    @Test
    fun toCoachTurns_emptyHistoryIsZeroTurns() {
        assertTrue(emptyList<CoachChatMessage>().toCoachTurns().isEmpty())
    }

    @Test
    fun toCoachTurns_dropsATailQuestionThatHasNoAnswerYet() {
        val turns = listOf(user("q1"), answer("a1"), user("q2")).toCoachTurns()

        assertEquals(
            "q2 还没答，不能当历史发 —— 发过去模型会以为它已经答过",
            listOf("q1"),
            turns.map { turn -> turn.question },
        )
        assertEquals(listOf("a1"), turns.map { turn -> turn.answer })
    }

    @Test
    fun toCoachTurns_neverSendsUiStateBubblesAsModelAnswers() {
        val turns = listOf(user("q1"), noNetwork, user("q2"), failed, user("q3"), answer("a3"))
            .toCoachTurns()

        assertEquals(
            "只有 q3/a3 是真的问答；未联网和失败那两轮整轮丢掉",
            listOf("q3" to "a3"),
            turns.map { turn -> turn.question to turn.answer },
        )
    }

    @Test
    fun toCoachTurns_capsAtThreeTurns() {
        val messages = (1..5).flatMap { index -> listOf(user("q$index"), answer("a$index")) }

        val turns = messages.toCoachTurns()

        assertEquals(3, turns.size)
        assertEquals(listOf("q3", "q4", "q5"), turns.map { turn -> turn.question })
    }

    @Test
    fun toCoachTurns_afterAppendChat_agreesWithWhatIsOnScreen() {
        var messages = emptyList<CoachChatMessage>()
        repeat(5) { index ->
            messages = messages.appendChat(user("q$index")).appendChat(answer("a$index"))
        }

        val turns = messages.toCoachTurns()

        assertEquals(
            "屏上留几轮就发几轮 —— 不能出现「看得见但没发过」或反之",
            messages.filter { it.kind == CoachChatKind.ANSWER }.map { message -> message.text },
            turns.map { turn -> turn.answer },
        )
    }
}
