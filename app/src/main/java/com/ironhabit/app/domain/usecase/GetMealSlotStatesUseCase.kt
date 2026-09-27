package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.repository.MealRepository
import javax.inject.Inject

/**
 * 某一天里**某个餐次槽位**现在的状态。
 *
 * 存在的理由是一条界面红线：`meals` 上有 `UNIQUE(date_epoch_day, meal_type)`，
 * 而「编辑这一餐」那排餐次 chip 以前四颗全可点 —— 于是一天四餐齐全时，
 * 把早餐改成午餐**必然**撞唯一索引，用户先点一下、再读一句报错
 * （审查报告 P2-1）。做不到的选项不该摆出来让人点，所以判据要能被界面读到。
 *
 * ⚠️ 软删行**仍然占着唯一索引槽位**（`MealDao.softDelete` 只翻 `is_active`，
 * 而列表查询只取 `is_active = 1`）：于是"用户以前删过午餐"这一格在界面上是**看不见**的。
 * 单独给一个 `DELETED` 状态，是为了让那一颗 chip 能说清"改过来会把你删掉的那餐恢复并替换"，
 * 而不是撞一句「这一餐已经有另一条记录了」——叫用户去删一条他根本看不见的餐。
 */
enum class MealSlotState {
    /** 空的：可以直接改到这个餐次。 */
    FREE,

    /** 被**另一条活着的**餐占着：不该给点。 */
    TAKEN,

    /** 只被一条**软删行**占着：可以给点，但要先说清楚会发生什么（恢复并替换那一行）。 */
    DELETED,
}

/**
 * 读出某一天各餐次槽位的占用情况，供「编辑这一餐」弹层决定哪颗 chip 能点。
 *
 * @param excludeMealId 正在被编辑的那一餐自己 —— 它现在占的槽位不算"被别的餐占着"
 *        （否则打开编辑时它自己那颗 chip 会被灰掉）。
 */
class GetMealSlotStatesUseCase @Inject constructor(
    private val mealRepository: MealRepository,
) {

    suspend operator fun invoke(epochDay: Long, excludeMealId: Long): Map<MealType, MealSlotState> =
        mealSlotStates(mealRepository.getMealsIncludingInactive(epochDay), excludeMealId)
}

/** 纯映射（[GetMealSlotStatesUseCase] 的全部判断都在这，为了能在 JVM 里逐格钉）。 */
internal fun mealSlotStates(dayMeals: List<Meal>, excludeMealId: Long): Map<MealType, MealSlotState> =
    MealType.entries.associateWith { type ->
        val occupant: Meal? = dayMeals.firstOrNull { meal -> meal.mealType == type && meal.id != excludeMealId }
        when {
            occupant == null -> MealSlotState.FREE
            occupant.isActive -> MealSlotState.TAKEN
            else -> MealSlotState.DELETED
        }
    }
