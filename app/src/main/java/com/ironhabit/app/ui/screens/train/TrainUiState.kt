package com.ironhabit.app.ui.screens.train

import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.WeekPlan
import java.text.Collator
import java.util.Locale

/**
 * 训练页历史列表项：某天的打卡条数。
 *
 * @property epochDay 日期口径
 * @property count 当天打卡条数
 */
data class HistoryEntry(
    val epochDay: Long,
    val count: Int,
)

/**
 * 「训练」页 UI 状态（不可变）。
 *
 * @property isLoading 加载中
 * @property selectedDay 当前选中星期（`1` = 周一 … `7` = 周日）
 * @property weekStartEpochDay 这一页显示的是**哪一周**（周一 epochDay），归属标签用
 * @property plans 所选星期的计划条目
 * @property exercises 动作（动作库；v6 起动作无"停用"概念，全量可见）
 * @property exerciseNameById `exerciseId → name` 查表（计划列表展示动作名用）
 * @property history 近 30 天打卡历史（按日期倒序）
 * @property plannedDaysByExercise **本周生效计划**里 `exerciseId → 出现的星期集合`（动作库「已加入」✓ 的数据源）
 * @property repeatDaysByExercise **「每周相同」那份**里 `exerciseId → 出现的星期集合`（弹层勾选初值参考）
 * @property addToPlanSheetExercise 非 `null` = 正在为该动作打开「加入计划」弹层
 * @property isSubmittingAdd 正在写库（弹层确认按钮禁用，防连点）
 * @property errorRes 页面级错误资源 id
 * @property snackbarRes 一次性 Snackbar 资源 id
 */
data class TrainUiState(
    val isLoading: Boolean = true,
    val selectedDay: Int = 1,
    val weekStartEpochDay: Long = 0L,
    val plans: List<WeekPlan> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
    val exerciseNameById: Map<Long, String> = emptyMap(),
    val history: List<HistoryEntry> = emptyList(),
    val plannedDaysByExercise: Map<Long, Set<Int>> = emptyMap(),
    val repeatDaysByExercise: Map<Long, Set<Int>> = emptyMap(),
    val addToPlanSheetExercise: Exercise? = null,
    val isSubmittingAdd: Boolean = false,
    val errorRes: Int? = null,
    val snackbarRes: Int? = null,
)

/**
 * 动作库肌群筛选 chips 的选项（方案 A，数据驱动）。
 *
 * 取动作的**主肌群**（[Exercise.primaryMuscleGroup]，=`muscleGroups` 第一个）去重，按中文拼音序排列
 * （[Collator]+[Locale.CHINA]，JVM/Android 一致，chips 顺序对中文用户可预期）；
 * 主肌群为空的动作不产生选项（只出现在「全部」里）。
 */
fun distinctMuscleGroups(exercises: List<Exercise>): List<String> =
    exercises.mapNotNull { it.primaryMuscleGroup }
        .distinct()
        .sortedWith(compareBy(Collator.getInstance(Locale.CHINA)) { it })

/**
 * 按主肌群过滤动作库（方案 A）。
 *
 * `muscle` 空白 = 不过滤（全部）；非空 = 只留主肌群严格相等（区分大小写）的动作。
 * 纯函数抽取便于 JVM 单测锁定「全部 ⇄ 单肌群」往返语义。
 */
fun filterExercisesByMuscle(exercises: List<Exercise>, muscle: String): List<Exercise> =
    if (muscle.isBlank()) exercises else exercises.filter { it.primaryMuscleGroup == muscle }

/**
 * 按**动作名 / 任一肌群标签**做子串过滤（v7 加：动作库要从 51 个扩到几百个，只靠分类滚动找不到）。
 *
 * - 首尾空白忽略、大小写不敏感（英文动作名与用户随手输入的大小写不该影响命中）；
 * - 空白查询 = 原样返回（**不过滤**，也不复制列表）；
 * - 辅肌群也参与匹配（用户搜"肱三头"理应看到「哑铃卧推」这类以胸为主的动作）。
 *
 * 纯函数抽取便于 JVM 单测锁定上述三条口径。
 */
fun searchExercises(exercises: List<Exercise>, query: String): List<Exercise> {
    val needle: String = query.trim()
    if (needle.isEmpty()) return exercises
    return exercises.filter { exercise ->
        exercise.name.contains(needle, ignoreCase = true) ||
            exercise.muscleGroups.any { it.contains(needle, ignoreCase = true) }
    }
}
