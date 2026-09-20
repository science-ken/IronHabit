package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.diet.DietPlanGenerator
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealIntakeCalculator
import com.ironhabit.app.domain.model.MealItem
import com.ironhabit.app.domain.model.TodayMeals
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.MealItemRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 组装「今日饮食」聚合视图：**餐列表 + 合计 + 已记条目 + 实际摄入 + 目标**。
 *
 * 目标**不落库**（§10-4），每次由 [DietPlanGenerator.dailyTarget] 现算 —— 输入全部来自既有本地数据：
 * 体重（`body_metrics` 最新 WEIGHT）/ 档案（`SettingsRepository.profile()`）/
 * 该日是否训练日（走 `TrainingDayResolver`，与今日页清单同一判据）。
 *
 * 全部是 `Flow`：任一份输入变化（体重更新、档案改目标、计划增删、记一条食物）都会自动重算并刷新今日页。
 */
class GetTodayMealsUseCase @Inject constructor(
    private val mealRepository: MealRepository,
    private val mealItemRepository: MealItemRepository,
    private val trainingDayResolver: TrainingDayResolver,
    private val settingsRepository: SettingsRepository,
    private val bodyMetricRepository: BodyMetricRepository,
) {

    operator fun invoke(epochDay: Long): Flow<TodayMeals> {
        val mealsFlow = mealRepository.observeMeals(epochDay)
        val totalsFlow = mealRepository.observeTotals(epochDay)
        val itemsFlow = mealItemRepository.observeByDate(epochDay)
        // 餐与条目必须**成对**送进下面的 combine：实际摄入的算法是"以餐为轴"的
        // （软删一餐要靠它不在列表里来排除条目），两者分开到达就会出现
        // 中间帧"新条目 + 旧餐列表"，那一帧的合计是错的、而且会被用户看见一眼。
        val mealsWithItemsFlow = combine(mealsFlow, itemsFlow) { meals: List<Meal>, items: List<MealItem> ->
            meals to items
        }
        val profileFlow = settingsRepository.profile()
        // 最新体重 = `observeByType` 已按日期倒序 → 取首条；无记录 → null（走 §7.5.3 兜底）。
        val weightFlow = bodyMetricRepository.observeByType(BodyMetricType.WEIGHT)
            .map { metrics -> metrics.firstOrNull()?.value }
        // 判据收在 TrainingDayResolver：打卡过算练了；没打卡但那天是今天/未来且排了计划，
        // 仍按训练日（早上还没练就压低热量是错的）；过去且没练才算休息日。
        val isTrainingDayFlow = trainingDayResolver.observe(epochDay)

        return combine(
            mealsWithItemsFlow,
            totalsFlow,
            profileFlow,
            weightFlow,
            isTrainingDayFlow,
        ) { mealsAndItems, totals, profile, weightKg, isTrainingDay ->
            val meals: List<Meal> = mealsAndItems.first
            val items: List<MealItem> = mealsAndItems.second
            TodayMeals(
                meals = meals,
                totals = totals,
                items = items,
                // 实际摄入走 MealIntakeCalculator 这一处：明细优先，
                // 没记明细但打了勾才退回整餐值并标成粗记，两者都没有就是 0。
                // AI 生成过这一餐**不**贡献任何数字 —— 那是"计划"不是"吃了"。
                intake = MealIntakeCalculator.compute(meals, items),
                // 分支只有一条：档案完整与否都调同一个纯函数（§7.5.4）。
                target = DietPlanGenerator.dailyTarget(
                    weightKg = weightKg,
                    gender = profile.gender,
                    age = profile.age,
                    heightCm = profile.heightCm,
                    goal = profile.goal,
                    isTrainingDay = isTrainingDay,
                ),
            )
        }
    }
}
