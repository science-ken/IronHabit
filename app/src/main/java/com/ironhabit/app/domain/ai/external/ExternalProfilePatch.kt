package com.ironhabit.app.domain.ai.external

import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.ProfileLimits
import com.ironhabit.app.domain.model.UserProfile

/**
 * 外部文档 `profile` 段读进来的**结构化补丁**，以及"它和现在的档案差在哪"。
 *
 * ## 为什么补丁要先过一遍再比
 * 模型写的值有两类问题必须在比较之前处理掉，否则"没变"会被显示成"要改"：
 * - 越界值（`trainingDaysPerWeek: 9`、`goalWeightKg: 400`）→ 按 [ProfileLimits] 钳制，
 *   **和写入用的是同一个函数**，比较口径与落库口径不可能分叉；
 * - 认不出的枚举名（`goal: "TONING"`）→ 不进补丁，逐条记 [ExternalPlanNote.Kind.PROFILE_VALUE_REJECTED]，
 *   不猜"TONING 大概是 SHAPE"。
 *
 * ## 为什么 diff 是 sealed 而不是 `field + Any`
 * 六个字段的旧值/新值类型各不相同（枚举 / 浮点 / 整数 / 两个集合 / 文本）。
 * 用 `Any` 就得在界面和落库处各写一次强转，而这两处**都必须穷尽六个分支** ——
 * sealed + 穷尽 `when` 让"加了字段忘了处理"在编译期就炸，和 `AdviceSource` 那次同理。
 */
data class ExternalProfilePatch(
    val goal: Goal? = null,
    /** `null` = 文档没提这一项；非 null（含空串）= 要改成那个值。 */
    val goalWeightKg: Float? = null,
    val trainingDaysPerWeek: Int? = null,
    val equipment: Set<Equipment>? = null,
    val injuryAreas: Set<InjuryArea>? = null,
    val injuryNote: String? = null,
) {

    val isEmpty: Boolean
        get() = this == ExternalProfilePatch()

    /**
     * 与当前档案逐项比较，只留下**真的会变**的那些（顺序固定，界面按序渲染）。
     *
     * 值相同的项直接不出现：让用户勾一个"目标：增肌 → 增肌"是噪音，
     * 而且会稀释"这次导入到底改了什么"这个问题。
     */
    fun diffsAgainst(current: UserProfile): List<ProfileFieldDiff> = buildList {
        goal?.let { to ->
            if (to != current.goal) add(ProfileFieldDiff.GoalChange(from = current.goal, to = to))
        }
        goalWeightKg?.let { raw ->
            val to = ProfileLimits.coerceGoalWeightKg(raw)
            if (current.goalWeightKg != to) add(ProfileFieldDiff.WeightChange(from = current.goalWeightKg, to = to))
        }
        trainingDaysPerWeek?.let { raw ->
            val to = ProfileLimits.coerceTrainingDaysPerWeek(raw)
            if (current.trainingDaysPerWeek != to) {
                add(ProfileFieldDiff.DaysChange(from = current.trainingDaysPerWeek, to = to))
            }
        }
        equipment?.let { to ->
            if (current.equipment != to) {
                add(ProfileFieldDiff.EquipmentChange(from = current.equipment, to = to))
            }
        }
        injuryAreas?.let { to ->
            if (current.injuryAreas != to) {
                add(ProfileFieldDiff.InjuryAreaChange(from = current.injuryAreas, to = to))
            }
        }
        injuryNote?.let { raw ->
            val to = ProfileLimits.coerceInjuryNote(raw)
            if (current.injuryNote.orEmpty() != to) {
                add(ProfileFieldDiff.InjuryNoteChange(from = current.injuryNote, to = to))
            }
        }
    }
}

/** 一条"这一项要从旧值改成新值"。界面渲染它，落库用例执行它。 */
sealed interface ProfileFieldDiff {

    data class GoalChange(val from: Goal, val to: Goal) : ProfileFieldDiff
    data class WeightChange(val from: Float?, val to: Float) : ProfileFieldDiff
    data class DaysChange(val from: Int, val to: Int) : ProfileFieldDiff
    data class EquipmentChange(val from: Set<Equipment>, val to: Set<Equipment>) : ProfileFieldDiff
    data class InjuryAreaChange(val from: Set<InjuryArea>, val to: Set<InjuryArea>) : ProfileFieldDiff

    /** `from` 为 `null` = 以前没写过；`to` 为空串 = 把备注清空。 */
    data class InjuryNoteChange(val from: String?, val to: String) : ProfileFieldDiff
}
