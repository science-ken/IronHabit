package com.ironhabit.app.ui.screens.train

import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作库搜索（[searchExercises]）与「加入计划」弹层时长初值（[initialDurationText]）的纯函数口径。
 *
 * 两者都是从 Compose 里抽出来的判据：搜索要在 v7 之后面对几百个动作，时长初值一旦判错
 * 会**直接禁用保存键**（见 [initialDurationText] 文档），所以都在 JVM 上钉死，不起 Compose。
 */
class TrainExerciseSearchTest {

    private fun exercise(
        name: String,
        muscles: List<String>,
        durationSec: Int? = null,
    ) = Exercise(
        id = name.hashCode().toLong(),
        name = name,
        category = ExerciseCategory.STRENGTH,
        muscleGroups = muscles,
        defaultDurationSec = durationSec,
    )

    private val library = listOf(
        exercise("杠铃卧推", listOf("胸部", "肱三头肌")),
        exercise("硬拉", listOf("背部")),
        exercise("Bow Flexion Stretch", listOf("腿部")),
    )

    @Test
    fun blankQueryReturnsTheSameListUnfiltered() {
        // 空白 = 不过滤；且**原对象返回**（不复制），让上层的 remember 比对便宜。
        assertSame(library, searchExercises(library, ""))
        assertSame(library, searchExercises(library, "   "))
    }

    @Test
    fun matchesByNameSubstring() {
        assertEquals(listOf("硬拉"), searchExercises(library, "硬").map { it.name })
    }

    @Test
    fun matchesSecondaryMuscleNotOnlyPrimary() {
        // 「肱三头肌」是「杠铃卧推」的**辅**肌群；只按主肌群筛会让用户搜不到它。
        assertTrue(searchExercises(library, "肱三头").any { it.name == "杠铃卧推" })
    }

    @Test
    fun matchIsCaseInsensitiveAndTrimsInput() {
        assertEquals(1, searchExercises(library, "bow").size)
        assertEquals(1, searchExercises(library, "  BOW  ").size)
        assertEquals(1, searchExercises(library, "BOW").size)
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertTrue(searchExercises(library, "不存在的动作").isEmpty())
    }

    @Test
    fun durationTextPrefillsWholeMinutesOnly() {
        assertEquals("40", initialDurationText(exercise("游泳", listOf("有氧"), durationSec = 2400)))
        assertEquals("1", initialDurationText(exercise("开合跳", listOf("有氧"), durationSec = 60)))
        assertEquals("", initialDurationText(exercise("平板支撑", listOf("核心"), durationSec = null)))
    }

    @Test
    fun subMinuteDurationLeavesFieldBlankInsteadOfZero() {
        // 45 秒是"一组维持 45 秒"，不是"这次练 45 分钟"。
        // 若预填 `45 / 60 = 0`，0 落在 InputLimits 的 1..600 之外 → 保存键被永久禁用，
        // 「侧平板支撑 / 登山跑 / 高抬腿」这三个动作就再也加不进计划了。
        assertEquals("", initialDurationText(exercise("侧平板支撑", listOf("核心"), durationSec = 45)))
        assertEquals("", initialDurationText(exercise("高抬腿", listOf("有氧"), durationSec = 59)))
    }
}
