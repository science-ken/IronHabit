package com.ironhabit.app.domain.ai.external

import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory

/**
 * 文档声明的**新动作**（`newExercises` 段）—— 库里没有、但用户确认后要建进动作库的那一行。
 *
 * ## 为什么要模型显式声明，而不是"遇到不认识的名字就建一个"
 * 建动作是往用户库里**永久加一行**：名字漂一个字符就多一个"杠铃深蹲 "，字段瞎填会让以后的
 * 伤病避让静默失效。所以只有它点名说"这是新动作，分类/肌群是这些"时才有资格进候选，
 * 而且**还要用户勾一下**才真的写。
 *
 * ## 肌群标签必须命中词表，一个不认识就整条拒收
 * [com.ironhabit.app.domain.model.MuscleGroup] 是全 App 唯一真源，改名/造新词会让存量行与
 * 新行的标签对不上（伤病映射读不到就不避让，**静默**）。半收的条目比不收更坏：
 * 一条"有分类没肌群"的动作看起来正常，实际永远躲不开膝伤动作。
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
