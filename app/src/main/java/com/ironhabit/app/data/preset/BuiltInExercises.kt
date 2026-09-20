package com.ironhabit.app.data.preset

import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseSource
import com.ironhabit.app.domain.model.MuscleGroup

/**
 * 内置动作静态清单（**共 51 个**，≥ 40 要求）。
 *
 * 三类：自重 19 + 力量 20 + 有氧 12。
 * 说明：动作名与肌群标签为**数据预置**，按架构 §7.5 允许直接写在 Kotlin 中（唯一例外）；
 * 肌群取值一律引 [MuscleGroup] 常量，器械取值引 [Equipment]，两者都由
 * `MuscleGroupVocabularyTest` 校验没有漂出词表。
 * v2 起所有动作 `source = BUILT_IN`、`isActive = true`、`id = 0`（交给 Room 自增），
 * `muscleGroups` 为「主肌群」单元素列表，`sortOrder` = 列表下标 + 1。
 *
 * ## v7 起每条都带 `equipment`（本动作**需要**什么）
 * 此前规则引擎只能按分类猜器械，于是「绳索下压」会排给只有哑铃的人，
 * 「高位下拉」和「哑铃卧推」在规则眼里完全等价。现在按名称的字面器械如实标注：
 * - 名称里写杠铃/哑铃/绳索的，按名称标；
 * - 高位下拉 / 坐姿划船 / 腿举 / 椭圆机 / 划船机 / 动感单车 → 健身房固定器械 [Equipment.MACHINE]；
 * - 跑步机跑步 → [Equipment.TREADMILL]；悬垂举腿 → [Equipment.PULLUP_BAR]；
 * - 其余（含户外跑步、骑行、游泳、垫上动作）确实用不到健身器械，标 [Equipment.NONE]。
 *
 * ⚠️ 垫上动作（平板支撑、卷腹…）**不**标 [Equipment.YOGA_MAT]：瑜伽垫不是"练不了"的门槛，
 * 而 [com.ironhabit.app.domain.ai.LocalRuleAdvisor] 会把标注的器械当成硬性可用性判据。
 */
object BuiltInExercises {

    /** 原始定义（`sortOrder` 统一为 0，由 [all] 重排）。 */
    private val raw: List<Exercise> = listOf(
        // ================= 自重（19） =================
        ex("俯卧撑", ExerciseCategory.BODYWEIGHT, MuscleGroup.CHEST, sets = 3, reps = 15),
        ex("宽距俯卧撑", ExerciseCategory.BODYWEIGHT, MuscleGroup.CHEST, sets = 3, reps = 12),
        ex("钻石俯卧撑", ExerciseCategory.BODYWEIGHT, MuscleGroup.TRICEPS, sets = 3, reps = 10),
        ex("上斜俯卧撑", ExerciseCategory.BODYWEIGHT, MuscleGroup.CHEST, sets = 3, reps = 15),
        ex("下斜俯卧撑", ExerciseCategory.BODYWEIGHT, MuscleGroup.SHOULDER, sets = 3, reps = 12),
        ex("深蹲", ExerciseCategory.BODYWEIGHT, MuscleGroup.LEG, sets = 3, reps = 20),
        ex("相扑深蹲", ExerciseCategory.BODYWEIGHT, MuscleGroup.GLUTE_LEG, sets = 3, reps = 15),
        ex("保加利亚分腿蹲", ExerciseCategory.BODYWEIGHT, MuscleGroup.LEG, sets = 3, reps = 10),
        ex("箭步蹲", ExerciseCategory.BODYWEIGHT, MuscleGroup.LEG, sets = 3, reps = 12),
        ex("臀桥", ExerciseCategory.BODYWEIGHT, MuscleGroup.GLUTE, sets = 3, reps = 20),
        ex("平板支撑", ExerciseCategory.BODYWEIGHT, MuscleGroup.CORE, sets = 3, reps = 1, durationSec = 60),
        ex("侧平板支撑", ExerciseCategory.BODYWEIGHT, MuscleGroup.CORE, sets = 3, reps = 1, durationSec = 45),
        ex("卷腹", ExerciseCategory.BODYWEIGHT, MuscleGroup.ABS, sets = 3, reps = 20),
        // 名字里没有"悬垂"二字就看不出要单杠 —— 这条是自重段里唯一需要器械的。
        ex("悬垂举腿", ExerciseCategory.BODYWEIGHT, MuscleGroup.ABS, sets = 3, reps = 12, equipment = listOf(Equipment.PULLUP_BAR)),
        ex("登山跑", ExerciseCategory.BODYWEIGHT, MuscleGroup.CORE, sets = 3, reps = 1, durationSec = 45),
        ex("波比跳", ExerciseCategory.BODYWEIGHT, MuscleGroup.FULL_BODY, sets = 4, reps = 12),
        // 修复 D1：补 3 个**不需要任何器械**的背 / 后肩动作。
        // 原内置库的背部动作（硬拉 / 划船 / 下拉…）全在「力量」段，无器械用户会被器械过滤一并排除，
        // 于是「无器械 + 背日」永远排不到背。这 3 条让无器械用户也能练到背 / 后肩。
        ex("超人式", ExerciseCategory.BODYWEIGHT, MuscleGroup.BACK, sets = 3, reps = 12),
        ex("俯卧挺身", ExerciseCategory.BODYWEIGHT, MuscleGroup.BACK, sets = 3, reps = 15),
        ex("俯卧Y字伸展", ExerciseCategory.BODYWEIGHT, MuscleGroup.REAR_DELT, sets = 3, reps = 15),

        // ================= 力量（20） =================
        ex("杠铃卧推", ExerciseCategory.STRENGTH, MuscleGroup.CHEST, sets = 4, reps = 8, equipment = listOf(Equipment.BARBELL)),
        ex("哑铃卧推", ExerciseCategory.STRENGTH, MuscleGroup.CHEST, sets = 3, reps = 10, equipment = listOf(Equipment.DUMBBELL)),
        ex("上斜哑铃卧推", ExerciseCategory.STRENGTH, MuscleGroup.UPPER_CHEST, sets = 3, reps = 10, equipment = listOf(Equipment.DUMBBELL)),
        ex("哑铃飞鸟", ExerciseCategory.STRENGTH, MuscleGroup.CHEST, sets = 3, reps = 12, equipment = listOf(Equipment.DUMBBELL)),
        ex("杠铃深蹲", ExerciseCategory.STRENGTH, MuscleGroup.LEG, sets = 4, reps = 8, equipment = listOf(Equipment.BARBELL)),
        ex("硬拉", ExerciseCategory.STRENGTH, MuscleGroup.BACK, sets = 4, reps = 6, equipment = listOf(Equipment.BARBELL)),
        ex("罗马尼亚硬拉", ExerciseCategory.STRENGTH, MuscleGroup.HAMSTRING, sets = 3, reps = 10, equipment = listOf(Equipment.BARBELL)),
        ex("杠铃划船", ExerciseCategory.STRENGTH, MuscleGroup.BACK, sets = 4, reps = 8, equipment = listOf(Equipment.BARBELL)),
        ex("单臂哑铃划船", ExerciseCategory.STRENGTH, MuscleGroup.BACK, sets = 3, reps = 12, equipment = listOf(Equipment.DUMBBELL)),
        ex("高位下拉", ExerciseCategory.STRENGTH, MuscleGroup.BACK, sets = 3, reps = 12, equipment = listOf(Equipment.MACHINE)),
        ex("坐姿划船", ExerciseCategory.STRENGTH, MuscleGroup.BACK, sets = 3, reps = 12, equipment = listOf(Equipment.MACHINE)),
        ex("杠铃肩上推举", ExerciseCategory.STRENGTH, MuscleGroup.SHOULDER, sets = 4, reps = 8, equipment = listOf(Equipment.BARBELL)),
        ex("哑铃侧平举", ExerciseCategory.STRENGTH, MuscleGroup.SHOULDER, sets = 3, reps = 15, equipment = listOf(Equipment.DUMBBELL)),
        ex("哑铃前平举", ExerciseCategory.STRENGTH, MuscleGroup.SHOULDER, sets = 3, reps = 12, equipment = listOf(Equipment.DUMBBELL)),
        ex("面拉", ExerciseCategory.STRENGTH, MuscleGroup.REAR_DELT, sets = 3, reps = 15, equipment = listOf(Equipment.CABLE)),
        ex("杠铃弯举", ExerciseCategory.STRENGTH, MuscleGroup.BICEPS, sets = 3, reps = 12, equipment = listOf(Equipment.BARBELL)),
        ex("锤式弯举", ExerciseCategory.STRENGTH, MuscleGroup.BICEPS, sets = 3, reps = 12, equipment = listOf(Equipment.DUMBBELL)),
        ex("绳索下压", ExerciseCategory.STRENGTH, MuscleGroup.TRICEPS, sets = 3, reps = 15, equipment = listOf(Equipment.CABLE)),
        ex("窄距卧推", ExerciseCategory.STRENGTH, MuscleGroup.TRICEPS, sets = 3, reps = 10, equipment = listOf(Equipment.BARBELL)),
        ex("腿举", ExerciseCategory.STRENGTH, MuscleGroup.LEG, sets = 4, reps = 12, equipment = listOf(Equipment.MACHINE)),

        // ================= 有氧（12） =================
        ex("跑步机跑步", ExerciseCategory.CARDIO, MuscleGroup.CARDIO, sets = 1, reps = 1, durationSec = 1800, equipment = listOf(Equipment.TREADMILL)),
        ex("户外跑步", ExerciseCategory.CARDIO, MuscleGroup.CARDIO, sets = 1, reps = 1, durationSec = 1800),
        ex("快走", ExerciseCategory.CARDIO, MuscleGroup.CARDIO, sets = 1, reps = 1, durationSec = 2400),
        ex("骑行", ExerciseCategory.CARDIO, MuscleGroup.CARDIO, sets = 1, reps = 1, durationSec = 2400),
        ex("椭圆机", ExerciseCategory.CARDIO, MuscleGroup.CARDIO, sets = 1, reps = 1, durationSec = 1500, equipment = listOf(Equipment.MACHINE)),
        ex("划船机", ExerciseCategory.CARDIO, MuscleGroup.CARDIO, sets = 1, reps = 1, durationSec = 1200, equipment = listOf(Equipment.MACHINE)),
        ex("跳绳", ExerciseCategory.CARDIO, MuscleGroup.CARDIO, sets = 5, reps = 1, durationSec = 120),
        ex("开合跳", ExerciseCategory.CARDIO, MuscleGroup.CARDIO, sets = 4, reps = 1, durationSec = 60),
        ex("高抬腿", ExerciseCategory.CARDIO, MuscleGroup.CARDIO, sets = 4, reps = 1, durationSec = 45),
        ex("爬楼梯", ExerciseCategory.CARDIO, MuscleGroup.CARDIO, sets = 1, reps = 1, durationSec = 900),
        ex("动感单车", ExerciseCategory.CARDIO, MuscleGroup.CARDIO, sets = 1, reps = 1, durationSec = 1800, equipment = listOf(Equipment.MACHINE)),
        ex("游泳", ExerciseCategory.CARDIO, MuscleGroup.CARDIO, sets = 1, reps = 1, durationSec = 2400),
    )

    /** 全部内置动作（`sortOrder` 由列表顺序推导，1 起）。 */
    val all: List<Exercise> = raw.mapIndexed { index, exercise ->
        exercise.copy(sortOrder = index + 1)
    }

    /**
     * 构造一条内置动作。
     *
     * [equipment] 默认 `NONE`（仅自重即可完成）—— 需要器械的动作必须显式写出来，
     * 空列表在领域模型里表示"**未标注**"，那是给用户自建动作留的语义，不是给内置动作的。
     */
    private fun ex(
        name: String,
        category: ExerciseCategory,
        muscleGroup: String,
        sets: Int,
        reps: Int,
        durationSec: Int? = null,
        equipment: List<Equipment> = listOf(Equipment.NONE),
    ): Exercise = Exercise(
        id = 0L,
        name = name,
        category = category,
        source = ExerciseSource.BUILT_IN,
        muscleGroups = listOf(muscleGroup),
        equipment = equipment,
        note = null,
        isActive = true,
        defaultSets = sets,
        defaultReps = reps,
        defaultDurationSec = durationSec,
        sortOrder = 0,
        timesUsed = 0,
        createdAt = 0L,
    )
}
