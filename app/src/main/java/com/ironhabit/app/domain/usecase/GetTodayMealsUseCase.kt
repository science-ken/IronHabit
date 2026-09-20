package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.diet.DietPlanGenerator
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.TodayMeals
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 组装「今日饮食」聚合视图：**餐列表 + 合计 + 目标**（对应预览 `mealBlock()` + `kcal()/prot()` + 日目标）。
 *
 * 目标**不落库**（§10-4），每次由 [DietPlanGenerator.dailyTarget] 现算 —— 输入全部来自既有本地数据：
 * 体重（`body_metrics` 最新 WEIGHT）/ 档案（`SettingsRepository.profile()`）/
 * 该日是否训练日（`week_plans` 中该星期是否有启用行）。
 *
 * 全部是 `Flow`：任一份输入变化（体重更新、档案改目标、计划增删）都会自动重算并刷新今日页。
 */
class GetTodayMealsUseCase @Inject constructor(
    private val mealRepository: MealRepository,
    private val trainingDayResolver: TrainingDayResolver,
    private val settingsRepository: SettingsRepository,
    private val bodyMetricRepository: BodyMetricRepository,
) {

    operator fun invoke(epochDay: Long): Flow<TodayMeals> {
        val mealsFlow = mealRepository.observeMeals(epochDay)
        val totalsFlow = mealRepository.observeTotals(epochDay)
        val profileFlow = settingsRepository.profile()
        // 最新体重 = `observeByType` 已按日期倒序 → 取首条；无记录 → null（走 §7.5.3 兜底）。
        val weightFlow = bodyMetricRepository.observeByType(BodyMetricType.WEIGHT)
            .map { metrics -> metrics.firstOrNull()?.value }
        // 判据收在 TrainingDayResolver：打卡过算练了；没打卡但那天是今天/未来且排了计划，
        // 仍按训练日（早上还没练就压低热量是错的）；过去且没练才算休息日。
        val isTrainingDayFlow = trainingDayResolver.observe(epochDay)

        return combine(
            mealsFlow,
            totalsFlow,
            profileFlow,
            weightFlow,
            isTrainingDayFlow,
        ) { meals, totals, profile, weightKg, isTrainingDay ->
            TodayMeals(
                meals = meals,
                totals = totals,
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
