package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ExternalPlanSchema
import com.ironhabit.app.domain.diet.DietPlanGenerator
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.FoodRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

/**
 * 产出**「导入饮食」那一份提问模板**（纯本地拼字符串，零网络、零 token）。
 *
 * ## 为什么和训练那份拆开（2026-09-26，用户拍板）
 * 合在一份长模板里两头要，实测**模型对后半段的遵循度明显更差**：用户那份真实回答里
 * `days` 完全照合同，吃却自己另起了一段 `{"nutrition":{"meals":[{"meal":"早餐","items":["鸡蛋3个"]}]}}`
 * —— 中文餐次名、份量烧在字符串里、还不分周一到周日。App 读不到，用户只能看见"我问到的吃没影了"。
 * 拆开后这份模板短得多，而且可以专门把"要 `meals`、不要 `nutrition`"写在最前面。
 *
 * ## 仍然守住的三条
 * 1. **数字本地算**：一餐的热量由食物库每 100g × 克数现算，所以模板明令"营养数字一律不要写"；
 *    往外**发**每日目标是允许的（那是本地公式算的，不是它估的）。
 * 2. **只发名字，不发份量单位**：库里 24 种单位名里是 `份（干）`、`碗（生）` 这种东西，
 *    发过去只会诱使它回"1 碗"，然后整条被拒。
 * 3. **忌口只发枚举名**：不在 `strings.xml` 之外再造一份中文忌口词表。
 */
class BuildExternalDietPromptUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val foodRepository: FoodRepository,
    private val bodyMetricRepository: BodyMetricRepository,
    private val mealRepository: MealRepository,
    private val trainingDayResolver: TrainingDayResolver,
) {

    /**
     * @param targetWeekStartEpochDay 用户挑定要写进哪一周（本周 / 下周）
     */
    suspend operator fun invoke(targetWeekStartEpochDay: Long): String {
        val profile = settingsRepository.profile().first()
        val weightKg: Float? = bodyMetricRepository.latest(BodyMetricType.WEIGHT)?.value
        val foodNames: List<String> = foodRepository.observeAll().first()
            .filter { food -> food.isActive }
            .map { food -> food.name.trim() }
            .filter { it.isNotEmpty() }

        return TEMPLATE
            .replace(PLACEHOLDER_SCHEMA, ExternalPlanSchema.DIET_SCHEMA)
            .replace(PLACEHOLDER_WEEK, weekLabel(targetWeekStartEpochDay))
            .replace(PLACEHOLDER_MEAL_TYPES, MealType.entries.joinToString("/") { type -> type.name })
            .replace(PLACEHOLDER_FOODS, foodNames.joinToString("、"))
            .replace(PLACEHOLDER_AVOID, avoidSummary(profile.dietaryAvoid))
            .replace(PLACEHOLDER_TARGETS, dailyTargets(profile, weightKg, targetWeekStartEpochDay))
            .replace(PLACEHOLDER_EXISTING, existingDietSummary(targetWeekStartEpochDay))
    }

    /** 模型没有"今天"的概念，必须给绝对日期。 */
    private fun weekLabel(weekStartEpochDay: Long): String {
        val from: String = LocalDate.fromEpochDays(weekStartEpochDay.toInt()).toString()
        val to: String = LocalDate.fromEpochDays(weekStartEpochDay.toInt() + 6).toString()
        return "$from ~ $to"
    }

    /**
     * 七天七个目标，**逐天现算**（训练日 1.55 / 休息日 1.375，系数不同）。
     * 给一个周均值就是骗它，也骗用户 —— 它会照着一个不存在的一天去配餐。
     */
    private suspend fun dailyTargets(
        profile: UserProfile,
        weightKg: Float?,
        weekStartEpochDay: Long,
    ): String = buildString {
        for (offset in 0L until DAYS_IN_WEEK) {
            val epochDay: Long = weekStartEpochDay + offset
            val isTrainingDay: Boolean = trainingDayResolver(epochDay)
            val target = DietPlanGenerator.dailyTarget(
                weightKg = weightKg,
                gender = profile.gender,
                age = profile.age,
                heightCm = profile.heightCm,
                goal = profile.goal,
                isTrainingDay = isTrainingDay,
            )
            if (offset > 0) append('\n')
            append("周${offset + 1}（${LocalDate.fromEpochDays(epochDay.toInt())}）：")
            append("${target.targetKcal} kcal、蛋白 ${target.targetProtein} g")
            append(if (isTrainingDay) "（训练日）" else "（休息日）")
        }
    }

    private fun avoidSummary(avoid: Set<DietRestriction>): String =
        if (avoid.isEmpty()) "（档案里没有登记任何忌口）" else avoid.joinToString("/") { tag -> tag.name }

    /**
     * 目标周**已经排好的吃**。
     *
     * 不告诉它现状，它会把已经排过的那几餐原样再排一遍（用户看到的是"改了个寂寞"），
     * 或者反过来以为那一周空着、把四餐硬凑满。
     */
    private suspend fun existingDietSummary(weekStartEpochDay: Long): String = buildString {
        for (offset in 0L until DAYS_IN_WEEK) {
            val meals = mealRepository.getMealsIncludingInactive(weekStartEpochDay + offset)
                .filter { meal -> meal.isActive }
            if (offset > 0) append('\n')
            append("周${offset + 1}：")
            if (meals.isEmpty()) {
                append("（还没排）")
            } else {
                append(
                    meals.joinToString("；") { meal ->
                        meal.mealType.name + " " + meal.items.joinToString("、")
                    },
                )
            }
        }
    }.trimEnd()

    private companion object {
        const val DAYS_IN_WEEK = 7L

        const val PLACEHOLDER_SCHEMA = "{{SCHEMA}}"
        const val PLACEHOLDER_WEEK = "{{WEEK}}"
        const val PLACEHOLDER_MEAL_TYPES = "{{MEAL_TYPES}}"
        const val PLACEHOLDER_FOODS = "{{FOODS}}"
        const val PLACEHOLDER_AVOID = "{{AVOID}}"
        const val PLACEHOLDER_TARGETS = "{{TARGETS}}"
        const val PLACEHOLDER_EXISTING = "{{EXISTING}}"

        val TEMPLATE: String = """
帮我安排下一周的**饮食**。只输出一个 JSON 对象，我要把它原样粘回我的健身 App 导入。

【最重要的一条】吃必须写在顶层 "meals" 数组里。
**不要**用 nutrition / diet / mealPlan / 每日营养 之类的字段名或段落，也不要写 Markdown 表格或纯文字清单 ——
App 只认 meals，写在别处的内容会被直接丢弃（会被丢弃这件事 App 会告诉你，但那一餐就真的导不进来了）。

【输出格式】
{"schema":"{{SCHEMA}}","analysis":"一句话说明这周为什么这样配","meals":[
  {"dayOfWeek":1,"entries":[
    {"mealType":"BREAKFAST","items":[{"food":"鸡蛋","grams":110}]}
  ]}
]}
1. 只输出 JSON：不要代码块围栏，不要解释文字、不要前后寒暄。
2. 顶层 "schema" 逐字照抄 {{SCHEMA}}，不要改大小写或版本号。
3. meals 每项是一天：{"dayOfWeek":1,"entries":[…]}
   • dayOfWeek 取 1..7（1=周一，7=周日），**七天都要分开排**；不要给一份"通用的一天"。
   • 一天里可以只排你愿意排的餐次。**没写到的餐次 App 原样保留**，不会清空 —— 不需要为了怕被删掉而把四餐硬凑满。
4. entries 每项是一餐：{"mealType":"BREAKFAST","items":[…],"reason":"一句话"}
   • mealType 只能取：{{MEAL_TYPES}}（早餐 / 午餐 / 加餐 / 晚餐）。**必须用这些英文值**，写"午餐"会被整餐丢掉。
   • 同一餐里不要出现重复的食物名 —— App 只留第一条，不会把份量加起来。
   • 一餐最多 8 条，超出的 App 不会导入。
5. items 每项是一条食物：{"food":"食物名","grams":200}
   • food 必须**逐字**取自下面【我的食物库】里的名字（别改一个字、别去标点、别加"（蒸）"之外的修饰）。
   • grams 必须是**整数克数**。不要写"一碗 / 一勺 / 适量 / 半个"—— App 只收克数。
   • 库里没有的食物也可以排：App 会把它列成"库里没有的食物"让我逐条确认、我自己补数值，那一餐才会算上它。
     想让这一步省事，可以在顶层加 "newFoods" 数组把每 100 克的数值先填好：
     {"name":"紫薯","kcalPer100g":60,"proteinPer100g":1.6,"carbsPer100g":13.0,"fatPer100g":0.1}
     这四项只是**预填在我那张确认表上**，App 不会拿它们直接算某一餐。
6. **热量和蛋白质数字一律不要写**。App 会用我自己食物库里的每 100g 数值按克数现算。
   你在条目或顶层写 "kcal" / "proteinG" / "dailyCalories" 之类的字段都会被忽略，写了也不会生效。
   配餐时请照着下面【每天的目标】配 —— 那是 App 本地公式算出来的，一天一个值，训练日和休息日不同。
7. 我的忌口（这些类别里的食物一条都不要排）：{{AVOID}}
   注意：App 只会挡住食物库里**标注过**这几类的条目；没标注的食物它挡不住，所以请你主动避开。
8. 如果我在下面【我的食物库】里找不到清单，**不要**只回一个空壳计划：请在 analysis 里直接说"没收到食物清单"，并告诉用户重新复制模板整段再发一次。

【每天的目标】（App 本地公式算的，不要自己另定目标）
{{TARGETS}}

【我的食物库】只能逐字用里面的名字：
{{FOODS}}

【要写进的那一周】{{WEEK}}，它目前已经有：
{{EXISTING}}
这些不要重复排；想调整就在对应餐次上改。
        """.trimIndent()
    }
}
