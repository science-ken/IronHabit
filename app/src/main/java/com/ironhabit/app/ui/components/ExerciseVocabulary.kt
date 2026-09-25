package com.ironhabit.app.ui.components

import androidx.annotation.StringRes
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.ExerciseCategory

/**
 * 动作词汇的中文说法 —— **只留这一份**。
 *
 * 与 [ProfileSummaryCard] 给档案词汇立的规矩同源：同一个枚举在四个页面各念一遍自己的叫法，
 * 迟早有一天「力量」在动作库里叫「力量训练」，而用户在同一次操作里看到两种写法。
 * 上一轮数出来 `categoryLabelRes` 在 4 个文件里各有一份 private 副本
 * （`AddEditExerciseScreen` / `ExerciseDetailScreen` / `TrainScreen` / `CategoryPieChart`），
 * 四份内容逐字相同 —— 那正是漂移的前夜。
 *
 * ## 为什么不需要一条测试来钉它
 * `when` **穷尽、不写 else**：加一个 [ExerciseCategory] 成员而这里没处理，直接编译不过。
 * 编译期能挡住的错误不该再写一份运行时检查（`MuscleGroupVocabularyTest` 存在是因为那边
 * 是字符串常量、编译器管不到；这里管得到）。
 */
@StringRes
internal fun categoryLabelRes(category: ExerciseCategory): Int = when (category) {
    ExerciseCategory.BODYWEIGHT -> R.string.category_bodyweight
    ExerciseCategory.STRENGTH -> R.string.category_strength
    ExerciseCategory.CARDIO -> R.string.category_cardio
    ExerciseCategory.CUSTOM -> R.string.category_custom
}
