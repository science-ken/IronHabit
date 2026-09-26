package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ExternalPlanSchema
import com.ironhabit.app.domain.diet.DietPlanGenerator
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.model.MuscleGroup
import com.ironhabit.app.domain.model.ProfileLimits
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.FoodRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

/**
 * 产出**复制给用户带去外部 AI 的那段提问模板**（纯本地拼字符串，零网络、零 token）。
 *
 * 四段拼一起：固定指令（含回程合同的字段说明）+ **饮食侧要用的三段纯文本**（忌口 / 七天各自目标 / 食物清单）
 * + 现有 `ironhabit-week-package/v1` 数据包原文 + **目标周已经排了什么**的摘要。
 *
 * ## 为什么饮食那三段排在数据包**之前**
 * 整段粘贴进聊天框被截断时丢的是**尾部**（这条坑换来过 `ExportWeekPackageUseCase` 强制紧凑版），
 * 而 `library` 已经占着数据包尾部。把食物清单也排进 JSON 后面 = 两个清单抢同一个位置，
 * 于是"能收没人发"会以"模型说它没收到食物清单"的形式回来。
 * 那三段也因此**不进** `ironhabit-week-package/v1`：那个 JSON 的 key 清单被
 * `P2AdversarialTest` 逐字冻结（它是"AI 会看到什么"的对外合同），加 key 只会红得莫名其妙。
 *
 * ## 为什么要补第三段
 * 数据包里没有 `existing`（本周现有计划）—— 它是为"复盘这一周练得怎么样"设计的。
 * 不告诉外部 AI 现状，它会把上版生成的动作原样再排一遍、或者把 7 天全排满。
 * 投影器的保护规则只挡**手改行**，挡不住这种重复，所以这道该在提示词里做。
 *
 * ## 为什么这里可以有中文
 * 和 `RemotePromptBuilder` 同一条豁免：这是**给模型的指令**，不是界面文案（架构 §7.5 管的是后者）。
 * 用户视角的那几句说明（怎么复制、粘回哪里）在 `strings.xml` 里。
 *
 * ⚠️ 模板里的字段名是**合同**（`schema` / `days` / `meals` / `exercise` / `food` / `grams` / `targetSets`…），
 * 改动必须和 [com.ironhabit.app.domain.ai.external.ExternalPlanDocumentParser] 同时改、
 * 同时换版本号 —— 已经发出去的旧模板还在用户手里。
 * 2026-09-26 就这么换过一次（`v1` → `v2`，加饮食两段）：旧文档会吃 `WRONG_SCHEMA`，
 * 所以那句拒收文案必须给"重新点一次复制模板"这条出路，否则用户手上那份就永远读不回来。
 */
class BuildExternalCoachPromptUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val planRepository: PlanRepository,
    private val exerciseRepository: ExerciseRepository,
    private val foodRepository: FoodRepository,
    private val bodyMetricRepository: BodyMetricRepository,
    private val trainingDayResolver: TrainingDayResolver,
    private val exportWeekPackage: ExportWeekPackageUseCase,
) {

    /**
     * @param review 当前展示的那一周复盘（数据包直接从它序列化；VM 状态里已经有，不重算）
     * @param targetWeekStartEpochDay 用户挑定要写进哪一周（本周 / 下周）
     */
    suspend operator fun invoke(review: WeeklyReview, targetWeekStartEpochDay: Long): String {
        val profile = settingsRepository.profile().first()
        val daysPerWeek: Int = ProfileLimits.coerceTrainingDaysPerWeek(profile.trainingDaysPerWeek)
        // 紧凑版：模板要整段粘进聊天框，缩进多出来的字节就是把 library 被截断的概率翻倍。
        val packageJson: String = exportWeekPackage(review, includeDetails = true, pretty = false)

        val weekRows: List<WeekPlan> = planRepository.getRowsForWeek(targetWeekStartEpochDay)
        val names: Map<Long, String> = exerciseRepository.observeActive().first()
            .associate { exercise -> exercise.id to exercise.name.trim() }
        val foodNames: List<String> = foodRepository.observeAll().first()
            .filter { food -> food.isActive }
            .map { food -> food.name.trim() }
            .filter { it.isNotEmpty() }
        val weightKg: Float? = bodyMetricRepository.latest(BodyMetricType.WEIGHT)?.value

        return TEMPLATE
            .replace(PLACEHOLDER_DAYS, daysPerWeek.toString())
            .replace(PLACEHOLDER_SCHEMA, ExternalPlanSchema.SCHEMA)
            .replace(PLACEHOLDER_WEEK, weekLabel(targetWeekStartEpochDay))
            .replace(PLACEHOLDER_EXISTING, existingSummary(weekRows, names))
            .replace(PLACEHOLDER_PACKAGE, packageJson)
            // 合法值**从枚举现生成**：手抄一份清单，等于给"加了成员忘了同步模板"留个坑，
            // 而那个坑的表现形式是模型写了个合法值、App 说认不出。
            .replace(PLACEHOLDER_GOALS, enumNames<Goal>())
            .replace(PLACEHOLDER_EQUIPMENTS, enumNames<Equipment>())
            .replace(PLACEHOLDER_INJURIES, enumNames<InjuryArea>())
            .replace(PLACEHOLDER_CATEGORIES, enumNames<ExerciseCategory>())
            // 肌群标签的唯一真源是 MuscleGroup 词表（中文数据，不是文案）；
            // 把清单发给模型，比让它自己造一个"股四头肌"再被整条拒收省事。
            .replace(PLACEHOLDER_MUSCLES, MuscleGroup.formOptions.joinToString("/"))
            .replace(PLACEHOLDER_MEAL_TYPES, enumNames<MealType>())
            .replace(PLACEHOLDER_FOODS, foodNames.joinToString("、"))
            .replace(PLACEHOLDER_AVOID, avoidSummary(profile.dietaryAvoid))
            .replace(PLACEHOLDER_TARGETS, dailyTargets(profile, weightKg, targetWeekStartEpochDay))
    }

    /**
     * 七天的每日目标，**逐天现算**发给模型。
     *
     * 为什么给的是数字而不是让它自己估：它估出来的量级会飘（一天 1200 或 4500 都"看起来合理"），
     * 而它估的量级会直接决定它给每一餐配多少食物。
     * 这些数字全部出自本地公式（[DietPlanGenerator.dailyTarget]，Mifflin-St Jeor × 活动系数 × 目标系数），
     * 所以"往外发数字"和 R1「数字本地算」**不冲突** —— 那条红线管的是往里收。
     *
     * 训练日与休息日系数不同（1.55 / 1.375），所以必须一天一个值，不能给一个周均值。
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

    /**
     * 忌口只发**枚举名**，不发中文。
     *
     * 造一份中文忌口词表就等于在 `strings.xml` 之外再立一个第二词汇表：
     * 模型读得懂 `SEAFOOD`，而它真正要避开的是我发过去的那些**食物名**，不是这个标签。
     */
    private fun avoidSummary(avoid: Set<DietRestriction>): String =
        if (avoid.isEmpty()) "（档案里没有登记任何忌口）" else avoid.joinToString("/") { it.name }

    private inline fun <reified T : Enum<T>> enumNames(): String =
        enumValues<T>().joinToString("/") { entry -> entry.name }

    /** 目标周的日期范围（模型没有"今天"的概念，必须给绝对日期）。 */
    private fun weekLabel(weekStartEpochDay: Long): String {
        val from: String = LocalDate.fromEpochDays(weekStartEpochDay.toInt()).toString()
        val to: String = LocalDate.fromEpochDays(weekStartEpochDay.toInt() + 6).toString()
        return "$from ~ $to"
    }

    /**
     * 那一周已经排了什么。
     *
     * 没专属行的天写「沿用每周相同模板」而不是留空 —— 留空会被模型读成"这天没安排，可以随便排"，
     * 而那几天其实由模板负责、导入时也不会被覆盖。
     */
    private fun existingSummary(weekRows: List<WeekPlan>, names: Map<Long, String>): String {
        val activeByDay: Map<Int, List<WeekPlan>> = weekRows
            .filter { plan -> plan.isActive }
            .groupBy { plan -> plan.dayOfWeek }

        if (activeByDay.isEmpty()) return "（这一周还没有任何已排好的训练，7 天都可以安排）"

        return buildString {
            for (day in MIN_DAY..MAX_DAY) {
                val rows: List<WeekPlan>? = activeByDay[day]
                if (rows == null) {
                    appendLine("周$day：沿用「每周相同」模板，未单独排")
                } else {
                    appendLine("周$day：" + rows.joinToString("、") { row -> describe(row, names) })
                }
            }
        }.trimEnd()
    }

    private fun describe(row: WeekPlan, names: Map<Long, String>): String {
        val name: String = names[row.exerciseId] ?: "（库里已无此动作）"
        val load: String = if (row.targetDurationMin != null) {
            "${row.targetDurationMin}分钟"
        } else {
            val weight: String = row.targetWeightKg?.let { "×${it}kg" } ?: ""
            "${row.targetSets}组×${row.targetReps}次$weight"
        }
        return "$name $load"
    }

    private companion object {
        const val MIN_DAY = 1
        const val MAX_DAY = 7
        const val DAYS_IN_WEEK = 7L

        const val PLACEHOLDER_DAYS = "{{DAYS}}"
        const val PLACEHOLDER_SCHEMA = "{{SCHEMA}}"
        const val PLACEHOLDER_WEEK = "{{WEEK}}"
        const val PLACEHOLDER_EXISTING = "{{EXISTING}}"
        const val PLACEHOLDER_PACKAGE = "{{PACKAGE}}"
        const val PLACEHOLDER_GOALS = "{{GOALS}}"
        const val PLACEHOLDER_EQUIPMENTS = "{{EQUIPMENTS}}"
        const val PLACEHOLDER_INJURIES = "{{INJURIES}}"
        const val PLACEHOLDER_CATEGORIES = "{{CATEGORIES}}"
        const val PLACEHOLDER_MUSCLES = "{{MUSCLES}}"
        const val PLACEHOLDER_MEAL_TYPES = "{{MEAL_TYPES}}"
        const val PLACEHOLDER_FOODS = "{{FOODS}}"
        const val PLACEHOLDER_AVOID = "{{AVOID}}"
        const val PLACEHOLDER_TARGETS = "{{TARGETS}}"

        val TEMPLATE: String = """
帮我为下一周安排训练计划和饮食。**只输出一个 JSON 对象**，我要把它原样粘回我的健身 App 导入。

【输出硬要求】
1. 只输出 JSON：不要 Markdown 代码块围栏，不要任何解释文字、不要前后寒暄。
2. 顶层必须有 "schema":"{{SCHEMA}}"，逐字照抄，不要改大小写或版本号。
3. days 数组每项是一天：{"dayOfWeek":1,"focus":"FULL_BODY","items":[…]}
   • dayOfWeek 取 1..7（1=周一，7=周日）；只安排 {{DAYS}} 个训练日，不要多排，也不要排满 7 天。
   • focus 只能取：FULL_BODY / LOWER_BODY / UPPER_PUSH / UPPER_PULL / CARDIO_CORE。
4. items 每项是一个动作：{"exercise":"动作名","targetSets":4,"targetReps":8,"targetWeightKg":80.0}
   • exercise 必须**逐字**取自下面【我的数据】里 library 数组的 name；库里没有的动作一律不要写，也不要建议新动作。
   • targetSets 是 1..31 的整数，targetReps 是 1..100 的整数；自重动作 targetWeightKg 填 null。
   • 组数/次数/重量按【我的数据】里真有的字段定：week.days[].items 是我实际举起的重量与 RPE，summary.progressed 是本周加过重的动作，summary.stalled 是卡住没动的动作。做满且 RPE 偏低可以小幅加重。
   • 可选 "reason"：用一句话（40 字以内）说明**为什么排这个动作**。App 里默认折叠，用户点开才看得到，所以别把同样的话再写进 analysis。
5. analysis：1~3 句简体中文，说明这份计划为什么这样排（App 会原样显示给用户看）。
6. 只有在你确实认为该调整时，才在顶层加一个 "profile" 对象；**只允许下面这些字段**，枚举值必须逐字照抄（大写、不改拼写）：
   • goal：{{GOALS}}
   • goalWeightKg：数字（kg）
   • trainingDaysPerWeek：3..6 的整数
   • equipment：数组，元素只能取自 {{EQUIPMENTS}}
   • injuryAreas：数组，元素只能取自 {{INJURIES}}；确认用户没有伤病时才给空数组 []
   • injuryNote：一句话（不超过 200 字）
   身高、体重、体脂、年龄、性别**一律不要写**：那是你的实测数据，App 不收 AI 填的这一项，写了会被逐条退回。
7. 如果你要用我库里**没有**的动作，必须同时在顶层加一个 "newExercises" 数组声明它，否则那一条会被 App 直接丢掉：
   {"name":"保加利亚分腿蹲","category":"STRENGTH","muscleGroups":["腿部","臀部"],"equipment":["DUMBBELL"]}
   • category 只能取 {{CATEGORIES}}
   • muscleGroups 只能取这些已有标签：{{MUSCLES}}。**不要造新词** —— 词表是固定的，造了新词这一条会被整条拒收。
   • equipment 可选，元素只能取自 {{EQUIPMENTS}}
   能用我库里已有的动作就别加新的：加进去的行会永久留在我的动作库里。
8. 饮食写在顶层 "meals" 数组里，一天一项：{"dayOfWeek":1,"entries":[{"mealType":"BREAKFAST","items":[…]}]}
   • mealType 只能取：{{MEAL_TYPES}}（早餐 / 午餐 / 加餐 / 晚餐，一天最多各一项；同一项写两次 App 只留第一条）。
   • items 每项是一条食物：{"food":"食物名","grams":200}。**grams 必须是整数克数**，不要写"一碗 / 一勺 / 适量"。
   • 可以只排你愿意排的餐次。**没写到的餐次 App 原样保留**，不会清空 —— 所以不需要为了"怕被删掉"而把四餐硬凑满。
   • 可选 "reason"：一句话（40 字以内）说明这一餐为什么这样配。
9. 饮食的**热量和蛋白质数字一律不要写**。App 会用我自己食物库里的每 100g 数值按克数算：
   你在条目上写 "kcal":600 之类的字段会被直接忽略，写了也不会生效。
   配餐时请照着下面【饮食侧要用的数据】里那七天的目标配（那是 App 本地公式算出来的）。
10. 如果我在【饮食侧要用的数据】里找不到 FOODS 那份食物清单（或【我的数据】里找不到 library 动作清单），**不要**只回一个空壳计划：请在 analysis 里直接说"没收到 library"，并告诉用户重新复制模板整段再发一次。
11. 食物清单里**没有**的东西也可以排，App 会把它列成"库里没有的食物"让我逐条确认，确认后才会进库、那一餐才会算上它。
    想让那一步省事，可以在顶层加 "newFoods" 数组把我库里的数值先填好：
    {"name":"紫薯","kcalPer100g":60,"proteinPer100g":1.6,"carbsPer100g":13.0,"fatPer100g":0.1}
    • 这四项是**每 100 克**的量，不是这一餐的量；数值超出常识范围（热量 >900 或宏量 >100）会被 App 清空、让我自己填。
    • 这些数值只是**预填在我那张确认表上**，App 不会拿它们直接算某一餐 —— 真按下去进库之后，那一餐才会用它算。
    能用清单里已有的就别造新的：加进去的行会永久留在我的食物库里。

【饮食侧要用的数据】这三段是给你配餐用的，**都排在上面那段 JSON 之前**，因为整段粘贴被截断时丢的是尾部：
我的忌口（这些类别里的食物一条都不要排）：{{AVOID}}
每天的目标（App 本地公式算的，训练日与休息日不同；请让一周的日均值贴近它，不要自己另定目标）：
{{TARGETS}}
FOODS 我的食物库（**只能逐字用里面的名字**，不在里面的会被列成"库里没有"等我确认）：{{FOODS}}

【我的数据】下面这段 JSON 里有三样你要用到的东西：**library**（我能用的动作清单，只能逐字用里面的 name）、**profile**（目标 / 器械 / 伤病）、**week 与 summary**（这一周实际练了什么、哪些动作在进步、哪些卡住了）。
{{PACKAGE}}

【要写进的那一周】{{WEEK}}，它目前已经有：
{{EXISTING}}
这些不要重复排、也不要删；要改就改组数次数重量。

【输出示例（只示意结构，动作名和食物名都要换成我库里真有的）】
{"schema":"{{SCHEMA}}","analysis":"结合你的增肌目标与上周深蹲做满且强度有余量，本周加重并补一个髋部动作；饮食按训练日抬高碳水。","days":[{"dayOfWeek":1,"focus":"LOWER_BODY","items":[{"exercise":"杠铃深蹲","targetSets":4,"targetReps":8,"targetWeightKg":80.0,"reason":"上周做满且 RPE 6，小幅加重"}]}],"meals":[{"dayOfWeek":1,"entries":[{"mealType":"LUNCH","items":[{"food":"米饭（蒸）","grams":250},{"food":"鸡胸肉","grams":150}],"reason":"训练日主食加量"}]}]}
        """.trimIndent()
    }
}
