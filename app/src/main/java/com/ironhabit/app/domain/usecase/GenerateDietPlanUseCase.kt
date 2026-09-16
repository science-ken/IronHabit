package com.ironhabit.app.domain.usecase

import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.diet.DietPlanGenerator
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.util.DateUtils
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 「生成 / 重新生成饮食计划」的结果摘要（纯数据，供 UI 展示"写了几餐 / 保留了几餐 / 是否用了默认值"）。
 *
 * @property writtenCount 本次实际写入的餐数
 * @property preservedCount 被完整保留的**用户手改行**餐数（含软删行），对应「不会被覆盖」提示
 * @property target 本次使用的目标（供 UI 展示"已摄入 / 目标"）
 */
data class GeneratedDietSummary(
    val writtenCount: Int = 0,
    val preservedCount: Int = 0,
    val target: DietTarget = DietTarget(),
)

/**
 * **生成一日饮食计划**（对应预览 `doDiet()`，`docs/schema-v3-meals.md` §7.3）。
 *
 * 职责：读输入 → [DietPlanGenerator] 算目标与各餐草案 → **只对可写槽位显式 upsert**。
 *
 * ## 🔒 不变量（每条都有代码约束）
 * 1. **手改行完整保留**：`isUserEdited == true` 的行（**含软删行**）一行都不碰 —— 不覆盖、不复活；
 * 2. **禁用 REPLACE / 禁用"先删再建"**：本用例**没有任何 DELETE 调用**，
 *    写入统一走 [MealRepository.upsertGenerated]（内部是显式 upsert，命中则保留用户勾选）；
 * 3. **纯函数外置**：规则判断全在 [DietPlanGenerator]，本用例只负责取数与写入；
 * 4. **缺项不中断**：档案/体重缺失只会让 [DietTarget.usedDefaults] 为 `true`（UI 提示），
 *    **绝不拒绝生成、绝不弹错**（§7.5.3）。
 */
class GenerateDietPlanUseCase @Inject constructor(
    private val mealRepository: MealRepository,
    private val planRepository: PlanRepository,
    private val settingsRepository: SettingsRepository,
    private val bodyMetricRepository: BodyMetricRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend operator fun invoke(epochDay: Long): GeneratedDietSummary = withContext(ioDispatcher) {
        val profile = settingsRepository.profile().first()
        val weightKg: Float? = bodyMetricRepository.latest(BodyMetricType.WEIGHT)?.value
        val weekday: Int = DateUtils.weekdayMon1(epochDay)
        val isTrainingDay: Boolean = planRepository.observePlansForDay(weekday).first().isNotEmpty()

        val target: DietTarget = DietPlanGenerator.dailyTarget(
            weightKg = weightKg,
            gender = profile.gender,
            age = profile.age,
            heightCm = profile.heightCm,
            goal = profile.goal,
            isTrainingDay = isTrainingDay,
        )

        // 🔒 必须拿**全量**（含软删行），否则会误写手改/软删槽位把它"复活"。
        val existing = mealRepository.getMealsIncludingInactive(epochDay)
        val blockedTypes: Set<MealType> = existing
            .filter { it.isUserEdited }
            .map { it.mealType }
            .toSet()

        val nowMillis: Long = clock.now().toEpochMilliseconds()
        val drafts = MealType.entries
            .filter { type -> type !in blockedTypes }
            .map { type -> DietPlanGenerator.buildMeal(epochDay, type, target, nowMillis) }

        val written: Int = mealRepository.upsertGenerated(drafts)

        GeneratedDietSummary(
            writtenCount = written,
            preservedCount = blockedTypes.size,
            target = target,
        )
    }
}
