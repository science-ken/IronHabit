package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.BodyReview
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.DietReview
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseTrend
import com.ironhabit.app.domain.model.ReviewNote
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.WeekDayDetail
import com.ironhabit.app.domain.model.WeekItemDetail
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.model.MealIntake
import com.ironhabit.app.domain.model.MealIntakeCalculator
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.MealItemRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.PlanRepository
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * **把一周的数据算成一张复盘表**（P2，零联网、零 token）。
 *
 * 输入全是既有仓库（打卡 / 动作库 / 体重 / 饮食 / 计划），输出 [WeeklyReview]（纯数据）。
 * 界面（周复盘卡"AI 会看到什么"）与数据包导出都只消费它的结果 —— 所以"数字从哪来"只有这一处。
 *
 * ## 🔒 不变量
 * 1. **只读**：本用例**没有任何写操作**（不写打卡、不写计划、不写体重）。
 * 2. **不猜**：拿不到的数据一律 `null`（体重、RPE、饮食），**不用 0 冒充**
 *    （`0f` 会被读成"没变化/没吃"，那是编出来的结论）。
 * 3. **时间只能来自注入的 [clock]**：不许 `System.currentTimeMillis()` / `LocalDate.now()`，
 *    否则没法在单测里固定"今天是哪天"。
 * 4. **确定性**：同输入必同输出（集合遍历顺序固定：动作按 id、明细按日期+动作 id）。
 * 5. **7 天齐全**：`days` 恒为 7 项（含没打卡的空天），界面与导出都不需要补空逻辑。
 *
 * ## 算法（冻结版，逐条都有单测）
 * - **周一取整**：`weekStart = epochDay - ((epochDay + 3) % 7 + 7) % 7`
 *   （epochDay `0` = 1970-01-01 周四 → 该周周一 = `-3`，公式给 `-3` ✅，负数也正确）；
 * - **趋势**：取「本周 + 前 3 周」共 4 个周桶；每个动作每周的值 = 该周内 `weightKg` 的最大值
 *   （整周都没重量 → 该周"无记录"，**不记 0**）。本周有记录的动作才进 [TrainingReview.progressed] /
 *   [TrainingReview.stalled]；`stagnantWeeks` 只在**有记录的周**之间连数
 *   （中间断档不算"停滞"，否则一个刚开始练的动作第一周就会被判停滞）；
 * - **饮食**：一天算不算"记过"看的是**实际摄入**（Q22 = C）—— 有明细算明细之和，
 *   没明细但打了勾算那餐的整餐值（粗记），两者都没有的天**不计入**；
 *   AI 排了餐但一口没记 → 那天不存在。日均是**有记录的天**的平均，不是 7 天平均
 *   （没记录的天不该把均值拉低）；`preciseDays` 单独带出去，供界面标「约」（Q31 = B）。
 *
 * @param weekStartEpochDay 目标周的周一（epochDay 口径）；`null` = 今天所在的那一周
 */
class BuildWeeklyReviewUseCase @Inject constructor(
    private val checkInRepository: CheckInRepository,
    private val exerciseRepository: ExerciseRepository,
    private val bodyMetricRepository: BodyMetricRepository,
    private val mealRepository: MealRepository,
    private val mealItemRepository: MealItemRepository,
    private val planRepository: PlanRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
) {

    suspend operator fun invoke(weekStartEpochDay: Long? = null): WeeklyReview {
        // ⚠️ 本机 kotlinx-datetime 的 `toEpochDays()` 返回 `Int`（仓库层用 `Long`）→ 显式转。
        val today: Long = clock.now().toLocalDateTime(timeZone).date.toEpochDays().toLong()
        val weekStart: Long = weekStartOf(weekStartEpochDay ?: today)
        val weekEnd: Long = weekStart + DAYS_IN_WEEK - 1L

        val weekCheckIns: List<CheckIn> = checkInRepository.observeBetween(weekStart, weekEnd).first()
        val trendCheckIns: List<CheckIn> = checkInRepository
            .observeBetween(weekStart - TREND_PREVIOUS_WEEKS * DAYS_IN_WEEK, weekEnd)
            .first()
        val exercises: Map<Long, Exercise> = exerciseRepository.observeActive().first()
            .associateBy { exercise -> exercise.id }

        // 计划口径必须走 resolver —— 模板回落 / 逐天覆盖 / 软删行三条规则只在
        // `WeekPlanWeekResolver` 有一份，绕开它分母会和今日页清单对不上。
        val planRows: List<WeekPlan> = planRepository.observeEffectivePlanForWeek(weekStart).first()

        // 分母 = 生效行 **+** 被这周打卡**消费过**的停用行。
        // 只数生效行会算出「10/3」这种"实际是计划三倍"的数：删槽位（`softDelete`）或
        // 重新生成回收旧 AI 行（`deactivateGenerated`）会把**已经练过**的那条计划请出分母，
        // 而打卡记录不会跟着退出分子（2026-09-21 真机实测：9/14 当天生效行只剩 3 组，实际 10 组）。
        // 只补"被消费过"的：用户主动删掉、从没练过的槽位不该回来虚增分母。
        // ⚠️ 这条与今日页**刻意不同**：今日页答"今天还剩什么"（删掉的就不该出现），
        // 复盘答"那天计划了多少、实际做了多少"（做过的计划不能凭空消失）。
        val effectiveIds: Set<Long> = planRows.map { row -> row.id }.toSet()
        val consumedPlanIds: Set<Long> = weekCheckIns.mapNotNull { checkIn -> checkIn.planId }.toSet()
        val retiredConsumedRows: List<WeekPlan> = if (consumedPlanIds.isEmpty()) {
            emptyList()
        } else {
            // 打卡既可能挂在"本周专属行"上，也可能挂在回落用的「每周相同」模板行上 → 两份都要看。
            (planRepository.getRowsForWeek(weekStart) + planRepository.getRepeatRows())
                .filter { row -> row.id in consumedPlanIds && row.id !in effectiveIds }
                .distinctBy { row -> row.id }
        }
        val plannedRows: List<WeekPlan> = planRows + retiredConsumedRows
        val plannedSetsByDay: Map<Int, Int> = plannedRows
            .groupBy { plan -> plan.dayOfWeek }
            .mapValues { (_, rows) -> rows.sumOf { row -> row.targetSets } }

        val training: TrainingReview = buildTraining(
            weekStartEpochDay = weekStart,
            weekCheckIns = weekCheckIns,
            trendCheckIns = trendCheckIns,
            exercises = exercises,
            // B-9：「计划天数」必须是**复盘目标周**实际排课的天数 —— 本用例可翻到上周复盘，
            // 不能拿「当前周」的天数冒充。`observePlannedWeekdays(weekStart)` 本来就是
            // `observeEffectivePlanForWeek(weekStart)` 的 distinct 投影，这里直接派生，少读一次仓库。
            plannedDays = plannedSetsByDay.size,
            plannedSets = plannedRows.sumOf { row -> row.targetSets },
        )
        // 只算一次：body / diet 都会做仓库查询，算两遍既慢又可能出现不一致的快照。
        // 所以**逐日事实和周汇总都从同一次读取派生** —— 周卡说 2,199、日卡加起来不是
        // 这种情况在结构上就不可能发生。
        val body: BodyFacts = buildBody(weekStart = weekStart, weekEnd = weekEnd)
        val dayDiets: List<DayDiet> = buildDayDiets(weekStart = weekStart)
        val diet: DietReview = aggregateDiet(dayDiets)

        return WeeklyReview(
            weekStartEpochDay = weekStart,
            weekEndEpochDay = weekEnd,
            training = training,
            body = body.review,
            diet = diet,
            days = buildDays(
                weekStart = weekStart,
                weekCheckIns = weekCheckIns,
                exercises = exercises,
                dayDiets = dayDiets,
                weightByDay = body.weightByDay,
                plannedSetsByDay = plannedSetsByDay,
            ),
            todayEpochDay = today,
            notes = buildNotes(
                training = training,
                body = body.review,
                diet = diet,
                weekEndEpochDay = weekEnd,
                today = today,
            ),
        )
    }

    // ---------------- 训练 ----------------

    private fun buildTraining(
        weekStartEpochDay: Long,
        weekCheckIns: List<CheckIn>,
        trendCheckIns: List<CheckIn>,
        exercises: Map<Long, Exercise>,
        plannedDays: Int,
        plannedSets: Int,
    ): TrainingReview {
        val totalVolume: Float = weekCheckIns.sumOf { checkIn ->
            val weight: Float = checkIn.weightKg ?: return@sumOf 0.0
            (weight * checkIn.completedReps * checkIn.completedSets).toDouble()
        }.toFloat()

        val rpeValues: List<Int> = weekCheckIns.mapNotNull { checkIn -> checkIn.rpe }

        val trends: List<ExerciseTrend> = buildTrends(
            weekStartEpochDay = weekStartEpochDay,
            trendCheckIns = trendCheckIns,
            exercises = exercises,
            weekCheckIns = weekCheckIns,
        )

        return TrainingReview(
            plannedDays = plannedDays,
            completedDays = weekCheckIns.map { it.dateEpochDay }.distinct().size,
            totalVolumeKg = totalVolume,
            totalSets = weekCheckIns.sumOf { it.completedSets },
            plannedSets = plannedSets,
            avgRpe = if (rpeValues.isEmpty()) {
                null
            } else {
                val average: Float = rpeValues.sum().toFloat() / rpeValues.size
                (average * 10).roundToInt() / 10f
            },
            progressed = trends
                .filter { it.latestWeightKg != null && it.previousWeightKg != null && it.latestWeightKg > it.previousWeightKg }
                .sortedBy { it.exerciseName },
            stalled = trends
                .filter { it.stagnantWeeks >= TrainingReview.STALLED_WEEKS_THRESHOLD }
                .sortedWith(compareByDescending<ExerciseTrend> { it.stagnantWeeks }.thenBy { it.exerciseName }),
        )
    }

    /**
     * 周对周趋势（4 个周桶）。
     *
     * 桶下标 `0..2` = 前 3 周（越早越小），`3` = 本周。
     */
    private fun buildTrends(
        weekStartEpochDay: Long,
        trendCheckIns: List<CheckIn>,
        exercises: Map<Long, Exercise>,
        weekCheckIns: List<CheckIn>,
    ): List<ExerciseTrend> {
        val currentIds: Set<Long> = weekCheckIns.mapTo(LinkedHashSet()) { it.exerciseId }
        if (currentIds.isEmpty()) return emptyList()

        val windowStart: Long = weekStartOf(weekStartEpochDay) - TREND_PREVIOUS_WEEKS * DAYS_IN_WEEK
        val weightByExerciseByBucket: MutableMap<Long, MutableMap<Int, Float>> = HashMap()
        for (checkIn in trendCheckIns) {
            val weight: Float = checkIn.weightKg ?: continue
            // ⚠️ 必须用 floorDiv：Kotlin 的 Long 除法**向零取整**，窗口开始前 1–6 天的记录会被
            // 算成「桶 0（3 周前）」而不是被下面的 `< 0` 拦掉 —— 取数区间一改就会静默污染趋势
            //（复核报告 F-7 指出；当前不可达只是因为没有触发条件）。
            val bucket: Int = Math.floorDiv(checkIn.dateEpochDay - windowStart, DAYS_IN_WEEK).toInt()
            if (bucket < 0 || bucket > TREND_PREVIOUS_WEEKS) continue
            val perBucket: MutableMap<Int, Float> = weightByExerciseByBucket
                .getOrPut(checkIn.exerciseId) { HashMap() }
            val existing: Float? = perBucket[bucket]
            if (existing == null || weight > existing) perBucket[bucket] = weight
        }

        return currentIds
            .sorted()
            .mapNotNull { exerciseId ->
                val name: String = exercises[exerciseId]?.name?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null   // 查不到名字的动作直接跳过（不写"未知动作"占位）
                val buckets: Map<Int, Float> = weightByExerciseByBucket[exerciseId].orEmpty()
                val latest: Float = buckets[CURRENT_BUCKET] ?: return@mapNotNull null
                val previous: Float? = buckets[PREVIOUS_BUCKET]
                ExerciseTrend(
                    exerciseId = exerciseId,
                    exerciseName = name,
                    previousWeightKg = previous,
                    latestWeightKg = latest,
                    stagnantWeeks = stagnantWeeks(latest = latest, buckets = buckets),
                )
            }
    }

    /**
     * 连续几周"有记录但没涨"。
     *
     * 只统计**有记录的周**：断档（该周没练这个动作）就停止计数 ——
     * 否则一个刚开始练的动作第一周就会被判成"停滞 3 周"。
     */
    private fun stagnantWeeks(latest: Float, buckets: Map<Int, Float>): Int {
        var stagnant: Int = 0
        var bucket: Int = PREVIOUS_BUCKET
        while (bucket >= 0) {
            val value: Float = buckets[bucket] ?: break
            if (latest > value) break
            stagnant++
            bucket--
        }
        return stagnant
    }

    // ---------------- 身体 ----------------

    /**
     * 体重：一次读取同时产出周汇总和逐日表。
     *
     * 一天最多一条（`BodyMetricEntity` 上 `type + date_epoch_day` 唯一索引），
     * 所以可以放心摊到日卡上，不出现"那天称了两次该显示哪个"。
     */
    private suspend fun buildBody(weekStart: Long, weekEnd: Long): BodyFacts {
        val rows = bodyMetricRepository.observeByType(BodyMetricType.WEIGHT).first()
            .filter { metric -> metric.dateEpochDay in weekStart..weekEnd }
            .sortedBy { metric -> metric.dateEpochDay }
        val weights: List<Float> = rows.map { metric -> metric.value }

        return BodyFacts(
            review = BodyReview(
                startWeightKg = weights.firstOrNull(),
                latestWeightKg = weights.lastOrNull(),
                // 记录条数要带上：只有一条时"变化"算不出来（见 BodyReview.deltaKg）。
                sampleCount = weights.size,
            ),
            weightByDay = rows.associate { metric -> metric.dateEpochDay to metric.value },
        )
    }

    /** [buildBody] 的两份产出：周汇总 + 逐日体重。 */
    private data class BodyFacts(val review: BodyReview, val weightByDay: Map<Long, Float>)

    // ---------------- 饮食 ----------------

    /**
     * 逐日摄入事实（7 天全出，没记的天 `kcal = 0`）。
     *
     * 这是饮食唯一的取数处：[aggregateDiet] 的周汇总和日卡的「当天 约 2,199」都从这份列表派生。
     */
    private suspend fun buildDayDiets(weekStart: Long): List<DayDiet> =
        (0 until DAYS_IN_WEEK).map { offset ->
            val day: Long = weekStart + offset
            val meals = mealRepository.getMealsIncludingInactive(day).filter { meal -> meal.isActive }
            // 与今日页共用同一个计算器：判定收在一处，否则磁贴说"约"而复盘说"精确"，
            // 同一份数据两种口径 —— 那正是这次要修掉的东西。
            val intake: MealIntake = MealIntakeCalculator.compute(
                meals = meals,
                items = mealItemRepository.getByDate(day),
            )
            DayDiet(
                dateEpochDay = day,
                // `kcal > 0` 而不是 `hasAnyRecord`：打了勾但那餐一个数字都没有的天，
                // 计入只会把一个 0 塞进平均里拉低它，却说不出任何事实。
                logged = intake.hasAnyRecord && intake.kcal > 0,
                kcal = intake.kcal,
                proteinG = intake.proteinG,
                precise = intake.preciseMeals > 0,
            )
        }

    /** 一次遍历的逐日产出。 */
    private data class DayDiet(
        val dateEpochDay: Long,
        val logged: Boolean,
        val kcal: Int,
        val proteinG: Double,
        val precise: Boolean,
    )

    private fun aggregateDiet(dayDiets: List<DayDiet>): DietReview {
        val logged: List<DayDiet> = dayDiets.filter { day -> day.logged }
        val days: Int = logged.size
        return DietReview(
            loggedDays = days,
            // 日均是**有记录的天**的平均，不是 7 天平均（没记录的天不该把均值拉低）。
            avgKcal = if (days == 0) null else (logged.sumOf { day -> day.kcal }.toFloat() / days).roundToInt(),
            avgProteinG = if (days == 0) null else (logged.sumOf { day -> day.proteinG } / days).roundToInt(),
            preciseDays = logged.count { day -> day.precise },
        )
    }

    // ---------------- 明细 ----------------

    private fun buildDays(
        weekStart: Long,
        weekCheckIns: List<CheckIn>,
        exercises: Map<Long, Exercise>,
        dayDiets: List<DayDiet>,
        weightByDay: Map<Long, Float>,
        plannedSetsByDay: Map<Int, Int>,
    ): List<WeekDayDetail> {
        val byDay: Map<Long, List<CheckIn>> = weekCheckIns.groupBy { checkIn -> checkIn.dateEpochDay }
        val dietByDay: Map<Long, DayDiet> = dayDiets.associateBy { diet -> diet.dateEpochDay }

        return (0 until DAYS_IN_WEEK).map { offset ->
            val day: Long = weekStart + offset
            val checkIns: List<CheckIn> = byDay[day].orEmpty()
            val diet: DayDiet? = dietByDay[day]?.takeIf { it.logged }
            val items: List<WeekItemDetail> = checkIns
                .sortedBy { checkIn -> checkIn.exerciseId }
                .mapNotNull { checkIn ->
                    val name: String = exercises[checkIn.exerciseId]?.name?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    WeekItemDetail(
                        exerciseId = checkIn.exerciseId,
                        exerciseName = name,
                        sets = checkIn.completedSets,
                        reps = checkIn.completedReps,
                        weightKg = checkIn.weightKg,
                        rpe = checkIn.rpe,
                        note = checkIn.notes,
                    )
                }
            WeekDayDetail(
                dateEpochDay = day,
                plannedSets = plannedSetsByDay[offset.toInt() + 1] ?: 0,
                // 组数按**全部**当天打卡算：查不到名字的行不进 items，但那几组真的做完了。
                completedSets = checkIns.sumOf { checkIn -> checkIn.completedSets },
                weightKg = weightByDay[day],
                kcal = diet?.kcal,
                proteinG = diet?.proteinG?.roundToInt(),
                dietPrecise = diet?.precise == true,
                items = items,
            )
        }
    }

    // ---------------- 诚实说明 ----------------

    private fun buildNotes(
        training: TrainingReview,
        body: BodyReview,
        diet: DietReview,
        weekEndEpochDay: Long,
        today: Long,
    ): List<ReviewNote> = buildList {
        if (training.completedDays == 0) add(ReviewNote.NO_CHECKIN)
        if (training.completedDays > 0 && training.avgRpe == null) add(ReviewNote.NO_RPE)
        if (body.startWeightKg == null) add(ReviewNote.NO_WEIGHT)
        if (diet.loggedDays == 0) add(ReviewNote.NO_DIET)
        if (weekEndEpochDay > today) add(ReviewNote.WEEK_IN_PROGRESS)
    }

    /**
     * 周界工具（公开 [weekStartOf]：界面层要算"上一周 / 下一周"，必须用**同一套取整规则**，
     * 不允许在两处各写一遍 —— 两处不一致就会出现"点了上一周但数字没变"这种诡异 bug）。
     */
    companion object {
        private const val DAYS_IN_WEEK: Long = 7

        /** 趋势窗口里"本周之前"还有几个周桶。 */
        private const val TREND_PREVIOUS_WEEKS: Int = 3

        /** 本周所在的桶下标。 */
        private const val CURRENT_BUCKET: Int = TREND_PREVIOUS_WEEKS

        /** 上周所在的桶下标。 */
        private const val PREVIOUS_BUCKET: Int = TREND_PREVIOUS_WEEKS - 1

        /** epochDay `0`（1970-01-01，周四）距其所在周的周一（`-3`）的偏移。 */
        private const val MONDAY_ALIGN_OFFSET: Long = 3

        /**
         * 取某个 epochDay 所在周的周一。
         *
         * `weekStart = epochDay − (((epochDay + 3) % 7 + 7) % 7)` —— 负数 epochDay 也正确
         * （`%` 在 Kotlin 里对负数取余仍是负数，所以外面再补一次 `+7 %7`）。
         */
        fun weekStartOf(epochDay: Long): Long =
            epochDay - (((epochDay + MONDAY_ALIGN_OFFSET) % DAYS_IN_WEEK + DAYS_IN_WEEK) % DAYS_IN_WEEK)
    }
}
