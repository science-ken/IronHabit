package com.ironhabit.app.ui.components

import androidx.annotation.StringRes
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.DietRestriction

/**
 * 忌口词汇的中文说法 —— **只留这一份**。
 *
 * 与 [categoryLabelRes] 立的规矩同源：同一个枚举在多处各念一遍自己的叫法，迟早漂移。
 * 这一份从「档案 → 饮食忌口」那一格（多选）长出来，现在导入弹层里的**建食物库表单**
 * 也要用同一套标签给用户勾 —— 两处是同一个词表，绝不能出现"档案里叫『海鲜』、
 * 建库时叫『水产品』"。
 *
 * ## 为什么不需要一条测试来钉它
 * `when` **穷尽、不写 else**：加一个 [DietRestriction] 成员而这里没处理，直接编译不过。
 */
@StringRes
internal fun dietRestrictionLabelRes(restriction: DietRestriction): Int = when (restriction) {
    DietRestriction.PEANUT -> R.string.restriction_peanut
    DietRestriction.SEAFOOD -> R.string.restriction_seafood
    DietRestriction.DAIRY -> R.string.restriction_dairy
    DietRestriction.GLUTEN -> R.string.restriction_gluten
    DietRestriction.SPICY -> R.string.restriction_spicy
    DietRestriction.ALCOHOL -> R.string.restriction_alcohol
}
