package com.ironhabit.app.data.local

import com.ironhabit.app.data.local.entity.ExerciseEntity
import com.ironhabit.app.domain.model.ExerciseCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播种补空的判据（[mergeFillingBlanks]）：**只填空白，绝不动用户填过的值**。
 *
 * 这是 `DatabaseSeeder` 里唯一有分支的逻辑，抽成纯函数就是为了能在 JVM 上把
 * "什么算空"这条口径钉死 —— 一旦哪天有人把它改成"顺带覆盖内置的新值"，
 * 用户改过的动作默认组次会在下次冷启动被抹掉，而这条测试是唯一的闸。
 */
class MergeFillingBlanksTest {

    /** 一条"完整标注"的内置行（补空来源）。 */
    private val preset = ExerciseEntity(
        id = 0L,
        name = "卧推",
        category = ExerciseCategory.STRENGTH,
        muscleGroup = "胸部",
        equipment = "BARBELL",
        note = "肩胛下沉收紧",
        defaultSets = 4,
        defaultReps = 8,
        defaultDurationSec = 0,
        sortOrder = 7,
    )

    private fun existing(
        muscleGroup: String? = null,
        equipment: String? = null,
        note: String? = null,
        defaultSets: Int = 0,
        defaultReps: Int = 0,
        defaultDurationSec: Int = 0,
    ) = ExerciseEntity(
        id = 42L,
        name = "卧推",
        category = ExerciseCategory.STRENGTH,
        muscleGroup = muscleGroup,
        equipment = equipment,
        note = note,
        defaultSets = defaultSets,
        defaultReps = defaultReps,
        defaultDurationSec = defaultDurationSec,
        sortOrder = 3,
    )

    @Test
    fun fillsEveryBlankField() {
        val merged = mergeFillingBlanks(existing(), preset)
        assertEquals("胸部", merged?.muscleGroup)
        assertEquals("BARBELL", merged?.equipment)
        assertEquals("肩胛下沉收紧", merged?.note)
        assertEquals(4, merged?.defaultSets)
        assertEquals(8, merged?.defaultReps)
    }

    @Test
    fun neverOverwritesNonBlankValues() {
        // 用户自己填的一切（含与内置不同的值）必须原样留下。
        val userRow = existing(
            muscleGroup = "上胸",
            equipment = "DUMBBELL",
            note = "我自己的要点",
            defaultSets = 5,
            defaultReps = 20,
        )
        val merged = mergeFillingBlanks(userRow, preset)
        assertNull("没有任何空位 → 返回 null，调用方据此跳过 UPDATE", merged)
    }

    @Test
    fun blankStringCountsAsBlank() {
        // 只有空白的文本列 = 空（表单历史上可能存进 `"  "`），不能因为它非 null 就跳过补空。
        val merged = mergeFillingBlanks(
            existing(muscleGroup = "   ", equipment = "", defaultSets = 3, defaultReps = 12),
            preset,
        )
        assertEquals("胸部", merged?.muscleGroup)
        assertEquals("BARBELL", merged?.equipment)
        assertEquals("组数已填 → 不动", 3, merged?.defaultSets)
        assertEquals("次数已填 → 不动", 12, merged?.defaultReps)
    }

    @Test
    fun zeroDurationMeansUnsetSoItGetsFilled() {
        // 有氧内置默认 1800 秒；`default_duration_sec = 0` 是 mapper 把领域层 `null` 写成的形态。
        val cardioPreset = preset.copy(muscleGroup = "有氧", equipment = "TREADMILL", defaultDurationSec = 1800)
        val merged = mergeFillingBlanks(existing(defaultDurationSec = 0), cardioPreset)
        assertEquals(1800, merged?.defaultDurationSec)
    }

    @Test
    fun identityAndUserOwnedColumnsAreNeverTouched() {
        val merged = mergeFillingBlanks(
            existing(muscleGroup = null, equipment = null),
            preset,
        ) ?: error("本例应有空位可补")
        // 主键由调用方按 existing.id 覆盖，这里保证合并函数自己不改它、也不改分类/排序/名称。
        assertEquals(42L, merged.id)
        assertEquals("卧推", merged.name)
        assertEquals(ExerciseCategory.STRENGTH, merged.category)
        assertEquals("sort_order 属用户可见顺序，不由内置值改写", 3, merged.sortOrder)
    }
}
