package com.ironhabit.app.ui.screens.train

import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 动作库肌群筛选（方案 A）纯函数单测 —— 锁定「全部 ⇄ 单肌群」往返与选项去重语义。
 */
class TrainMuscleFilterTest {

    private fun exercise(id: Long, name: String, vararg muscles: String): Exercise = Exercise(
        id = id,
        name = name,
        category = ExerciseCategory.BODYWEIGHT,
        muscleGroups = muscles.toList(),
        isActive = true,
        defaultSets = 3,
        defaultReps = 12,
    )

    private val library = listOf(
        exercise(1L, "深蹲", "腿部", "臀部"),
        exercise(2L, "弓步", "腿部"),
        exercise(3L, "俯卧撑", "胸部", "手臂"),
        exercise(4L, "平板支撑", "核心"),
        exercise(5L, "无肌群动作"), // muscleGroups 为空
    )

    @Test
    fun distinctMuscleGroups_dedupesAndSorts() {
        val options = distinctMuscleGroups(library)

        // chips 只列**主肌群**（第一个）：深蹲主=腿部（臀部是辅肌群不产生选项）、
        // 俯卧撑主=胸部（手臂是辅肌群不产生选项）；无肌群动作不产生选项。
        // 顺序锁中文拼音序（Collator + Locale.CHINA）：he < tui < xiong
        assertEquals(listOf("核心", "腿部", "胸部"), options)
    }

    @Test
    fun distinctMuscleGroups_emptyLibrary_isEmpty() {
        assertEquals(emptyList<String>(), distinctMuscleGroups(emptyList()))
    }

    @Test
    fun filterByMuscle_keepsOnlyPrimaryMatch() {
        val filtered = filterExercisesByMuscle(library, "腿部")

        // 只按主肌群（第一个）匹配：臀部是深蹲的辅肌群，不算「腿部」动作
        assertEquals(listOf("深蹲", "弓步"), filtered.map { it.name })
    }

    @Test
    fun filterByMuscle_blankMeansAll() {
        assertEquals(5, filterExercisesByMuscle(library, "").size)
        assertEquals(5, filterExercisesByMuscle(library, "   ").size)
    }

    @Test
    fun filterByMuscle_unknownMuscle_isEmpty() {
        assertEquals(emptyList<Exercise>(), filterExercisesByMuscle(library, "脖子"))
    }

    @Test
    fun filterThenAll_roundTripRestoresFullList() {
        val filtered = filterExercisesByMuscle(library, "胸部")
        val restored = filterExercisesByMuscle(filtered + library, "")

        assertEquals(library, restored.takeLast(library.size))
    }
}
