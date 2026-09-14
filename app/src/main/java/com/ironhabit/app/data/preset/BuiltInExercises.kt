package com.ironhabit.app.data.preset

import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseSource

/**
 * 内置动作静态清单（**共 48 个**，≥ 40 要求）。
 *
 * 三类：自重 16 + 力量 20 + 有氧 12。
 * 说明：动作名与肌群标签为**数据预置**，按架构 §7.5 允许直接写在 Kotlin 中（唯一例外）。
 * v2 起所有动作 `source = BUILT_IN`、`isActive = true`、`id = 0`（交给 Room 自增），
 * `muscleGroups` 为「主肌群」单元素列表，`sortOrder` = 列表下标 + 1。
 */
object BuiltInExercises {

    /** 原始定义（`sortOrder` 统一为 0，由 [all] 重排）。 */
    private val raw: List<Exercise> = listOf(
        // ================= 自重（16） =================
        ex("俯卧撑", ExerciseCategory.BODYWEIGHT, "胸部", sets = 3, reps = 15),
        ex("宽距俯卧撑", ExerciseCategory.BODYWEIGHT, "胸部", sets = 3, reps = 12),
        ex("钻石俯卧撑", ExerciseCategory.BODYWEIGHT, "肱三头肌", sets = 3, reps = 10),
        ex("上斜俯卧撑", ExerciseCategory.BODYWEIGHT, "胸部", sets = 3, reps = 15),
        ex("下斜俯卧撑", ExerciseCategory.BODYWEIGHT, "肩部", sets = 3, reps = 12),
        ex("深蹲", ExerciseCategory.BODYWEIGHT, "腿部", sets = 3, reps = 20),
        ex("相扑深蹲", ExerciseCategory.BODYWEIGHT, "臀腿", sets = 3, reps = 15),
        ex("保加利亚分腿蹲", ExerciseCategory.BODYWEIGHT, "腿部", sets = 3, reps = 10),
        ex("箭步蹲", ExerciseCategory.BODYWEIGHT, "腿部", sets = 3, reps = 12),
        ex("臀桥", ExerciseCategory.BODYWEIGHT, "臀部", sets = 3, reps = 20),
        ex("平板支撑", ExerciseCategory.BODYWEIGHT, "核心", sets = 3, reps = 1, durationSec = 60),
        ex("侧平板支撑", ExerciseCategory.BODYWEIGHT, "核心", sets = 3, reps = 1, durationSec = 45),
        ex("卷腹", ExerciseCategory.BODYWEIGHT, "腹部", sets = 3, reps = 20),
        ex("悬垂举腿", ExerciseCategory.BODYWEIGHT, "腹部", sets = 3, reps = 12),
        ex("登山跑", ExerciseCategory.BODYWEIGHT, "核心", sets = 3, reps = 1, durationSec = 45),
        ex("波比跳", ExerciseCategory.BODYWEIGHT, "全身", sets = 4, reps = 12),

        // ================= 力量（20） =================
        ex("杠铃卧推", ExerciseCategory.STRENGTH, "胸部", sets = 4, reps = 8),
        ex("哑铃卧推", ExerciseCategory.STRENGTH, "胸部", sets = 3, reps = 10),
        ex("上斜哑铃卧推", ExerciseCategory.STRENGTH, "上胸", sets = 3, reps = 10),
        ex("哑铃飞鸟", ExerciseCategory.STRENGTH, "胸部", sets = 3, reps = 12),
        ex("杠铃深蹲", ExerciseCategory.STRENGTH, "腿部", sets = 4, reps = 8),
        ex("硬拉", ExerciseCategory.STRENGTH, "背部", sets = 4, reps = 6),
        ex("罗马尼亚硬拉", ExerciseCategory.STRENGTH, "腿后链", sets = 3, reps = 10),
        ex("杠铃划船", ExerciseCategory.STRENGTH, "背部", sets = 4, reps = 8),
        ex("单臂哑铃划船", ExerciseCategory.STRENGTH, "背部", sets = 3, reps = 12),
        ex("高位下拉", ExerciseCategory.STRENGTH, "背部", sets = 3, reps = 12),
        ex("坐姿划船", ExerciseCategory.STRENGTH, "背部", sets = 3, reps = 12),
        ex("杠铃肩上推举", ExerciseCategory.STRENGTH, "肩部", sets = 4, reps = 8),
        ex("哑铃侧平举", ExerciseCategory.STRENGTH, "肩部", sets = 3, reps = 15),
        ex("哑铃前平举", ExerciseCategory.STRENGTH, "肩部", sets = 3, reps = 12),
        ex("面拉", ExerciseCategory.STRENGTH, "后肩", sets = 3, reps = 15),
        ex("杠铃弯举", ExerciseCategory.STRENGTH, "肱二头肌", sets = 3, reps = 12),
        ex("锤式弯举", ExerciseCategory.STRENGTH, "肱二头肌", sets = 3, reps = 12),
        ex("绳索下压", ExerciseCategory.STRENGTH, "肱三头肌", sets = 3, reps = 15),
        ex("窄距卧推", ExerciseCategory.STRENGTH, "肱三头肌", sets = 3, reps = 10),
        ex("腿举", ExerciseCategory.STRENGTH, "腿部", sets = 4, reps = 12),

        // ================= 有氧（12） =================
        ex("跑步机跑步", ExerciseCategory.CARDIO, "有氧", sets = 1, reps = 1, durationSec = 1800),
        ex("户外跑步", ExerciseCategory.CARDIO, "有氧", sets = 1, reps = 1, durationSec = 1800),
        ex("快走", ExerciseCategory.CARDIO, "有氧", sets = 1, reps = 1, durationSec = 2400),
        ex("骑行", ExerciseCategory.CARDIO, "有氧", sets = 1, reps = 1, durationSec = 2400),
        ex("椭圆机", ExerciseCategory.CARDIO, "有氧", sets = 1, reps = 1, durationSec = 1500),
        ex("划船机", ExerciseCategory.CARDIO, "有氧", sets = 1, reps = 1, durationSec = 1200),
        ex("跳绳", ExerciseCategory.CARDIO, "有氧", sets = 5, reps = 1, durationSec = 120),
        ex("开合跳", ExerciseCategory.CARDIO, "有氧", sets = 4, reps = 1, durationSec = 60),
        ex("高抬腿", ExerciseCategory.CARDIO, "有氧", sets = 4, reps = 1, durationSec = 45),
        ex("爬楼梯", ExerciseCategory.CARDIO, "有氧", sets = 1, reps = 1, durationSec = 900),
        ex("动感单车", ExerciseCategory.CARDIO, "有氧", sets = 1, reps = 1, durationSec = 1800),
        ex("游泳", ExerciseCategory.CARDIO, "有氧", sets = 1, reps = 1, durationSec = 2400),
    )

    /** 全部内置动作（`sortOrder` 由列表顺序推导，1 起）。 */
    val all: List<Exercise> = raw.mapIndexed { index, exercise ->
        exercise.copy(sortOrder = index + 1)
    }

    /** 构造一条内置动作。 */
    private fun ex(
        name: String,
        category: ExerciseCategory,
        muscleGroup: String,
        sets: Int,
        reps: Int,
        durationSec: Int? = null,
    ): Exercise = Exercise(
        id = 0L,
        name = name,
        category = category,
        source = ExerciseSource.BUILT_IN,
        muscleGroups = listOf(muscleGroup),
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
