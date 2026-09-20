package com.ironhabit.app.domain.model

import com.ironhabit.app.data.preset.BuiltInExercises
import com.ironhabit.app.domain.ai.LocalRuleAdvisor
import com.ironhabit.app.domain.usecase.SupplementPool
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 肌群词表唯一性（防"三份抄本各自漂移"复发）。
 *
 * v2.0.8 之前内置动作、[LocalRuleAdvisor.FOCUS_TAGS]、动作表单 chips 各存了一份标签清单，
 * 表单那份漏了「臀腿」，导致内置动作「相扑深蹲」的肌群在表单上**既看不到也选不到**
 * —— 词表只有一个真源后，这类漂移由本测试当场拦下，而不是等到界面上发现少了个 chip。
 */
class MuscleGroupVocabularyTest {

    @Test
    fun formOptionsCoverEveryKnownLabel() {
        // 表单少一个标签 = 该标签的动作在编辑页没有对应 chip。
        assertTrue(
            "表单 chips 必须覆盖全词表，缺：${MuscleGroup.all - MuscleGroup.formOptions.toSet()}",
            MuscleGroup.formOptions.toSet() == MuscleGroup.all,
        )
    }

    @Test
    fun builtInExerciseMusclesAreAllInVocabulary() {
        val unknown = BuiltInExercises.all
            .flatMap { it.muscleGroups }
            .filterNot { MuscleGroup.isKnown(it) }
            .distinct()
        assertTrue("内置动作用了词表外的肌群标签：$unknown", unknown.isEmpty())
    }

    @Test
    fun supplementPoolMusclesAreAllInVocabulary() {
        val unknown = SupplementPool.entries
            .flatMap { it.muscleGroups }
            .filterNot { MuscleGroup.isKnown(it) }
            .distinct()
        assertTrue("AI 补充动作池用了词表外的肌群标签：$unknown", unknown.isEmpty())
    }

    @Test
    fun focusTagsAreAllInVocabulary() {
        // FOCUS_TAGS 决定"这两天会不会连排同一肌群"，词表外的值 = 那条规则静默失效。
        val unknown = LocalRuleAdvisor.FOCUS_TAGS.values
            .flatten()
            .filterNot { MuscleGroup.isKnown(it) }
            .distinct()
        assertTrue("训练重点映射用了词表外的肌群标签：$unknown", unknown.isEmpty())
    }
}
