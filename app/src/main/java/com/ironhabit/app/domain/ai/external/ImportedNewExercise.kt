package com.ironhabit.app.domain.ai.external

import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory

/**
 * 一条"库里没有、用户勾选后才建进动作库"的候选。
 *
 * ## 为什么不再要求文档声明（2026-09-26 刀 5）
 * 旧规则是"只有 `newExercises` 里点名声明过的陌生名才有资格进候选"，而 shipped 的提问模板里
 * 同时写着「库里没有的动作一律不要写，也不要建议新动作」和「要用没有的就必须声明」两句相反的话。
 * 模型听前一句 → 不声明 → 那些条目走"库里找不到"**静默丢掉**，用户完全看不出为什么少了一条。
 * 现在陌生名**一律**进候选，声明只剩**预填字段**的作用；写没写都由用户勾一下才建，
 * 触发建库的仍然是一次人工确认。
 *
 * ## 肌群标签：丢掉不认识的，而不是整条拒收
 * [com.ironhabit.app.domain.model.MuscleGroup] 是全 App 唯一真源。门票取消后整条拒收的后果
 * 从"少一条候选"变成了"这个动作永远建不了"，更坏；所以改成**丢掉词表外的标签 + 界面点名**，
 * 并在候选行上标「没标肌群 · 伤病避让可能对它无效」——那条提示才是真正挡住静默失效的地方。
 */
data class ImportedNewExercise(
    val name: String,
    val category: ExerciseCategory,
    val muscleGroups: List<String>,
    val equipment: Set<Equipment>,
)

/** 候选动作 + 用户勾没勾（默认不勾，理由同档案 diff：文档"想要"不等于用户"同意"）。 */
data class NewExerciseCandidate(
    val exercise: ImportedNewExercise,
    val checked: Boolean = false,
)

/**
 * 建库前的幂等判据：库里已有同名（**含已停用**）就不再建。
 *
 * 与 `SuggestExercisesUseCase` 同一条口径 —— 停用不等于没有，重建会把用户主动停掉的东西
 * 悄悄放回可用池。
 */
fun ImportedNewExercise.conflictsWith(library: List<Exercise>): Boolean =
    library.any { existing -> existing.name.trim() == name }
