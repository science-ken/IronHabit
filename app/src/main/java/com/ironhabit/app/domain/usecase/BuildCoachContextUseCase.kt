package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.MealIntakeCalculator
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.MealItemRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.domain.util.toExpectedWeekdaysOrNull
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 本周计划的一行摘要（纯数据，供提示词组装）。
 *
 * ⚠️ [exerciseName] 是**用户数据**（动作名），不是界面文案 —— 因此不进 `strings.xml`
 * （与 `RemotePromptBuilder` 里"载荷里的中文是数据"同口径）。
 */
data class CoachPlanLine(
    /** 星期，`1` = 周一 … `7` = 周日。 */
    val dayOfWeek: Int,
    val exerciseName: String,
    val targetSets: Int,
    val targetReps: Int,
)

/**
 * 「教练上下文」—— 问答（子项 A）与进度解读（子项 C）**共用**的一份用户现状快照。
 *
 * 纯数据、零文案：远程提示词把它序列化成 JSON 交给 DeepSeek；
 * 本地解读把它渲染成结构化文案（文案一律在 UI 层走 `strings.xml`）。
 *
 * @property profile 用户身体档案
 * @property weeklyPlan 本周训练计划摘要行
 * @property checkInCount 窗口内**打卡记录条数**
 * @property windowDays 统计窗口天数（近 N 天）
 * @property averageRpe 窗口内平均主观强度（无 RPE 记录为 `null`）
 * @property weightDeltaKg 体重变化（最新一条 − 上一条；不足两条为 `null`）
 * @property currentStreak 当前连续打卡天数
 * @property todayIntakeKcal 今日**实际**摄入热量（明细优先 → 打了勾的整餐值 → 0；见 `MealIntakeCalculator`）
 * @property todayPlanKcal 今日计划总热量（全部启用餐，与吃没吃无关）
 */
data class CoachContext(
    val profile: UserProfile = UserProfile(),
    val weeklyPlan: List<CoachPlanLine> = emptyList(),
    val checkInCount: Int = 0,
    val windowDays: Int = BuildCoachContextUseCase.DEFAULT_WINDOW_DAYS,
    val averageRpe: Double? = null,
    val weightDeltaKg: Float? = null,
    val currentStreak: Int = 0,
    val todayIntakeKcal: Int = 0,
    val todayPlanKcal: Int = 0,
)

/**
 * 组装 [CoachContext]：读档案 / 计划 / 打卡 / 体重 / 饮食合计，做**纯聚合**（无文案、无网络）。
 *
 * 供 [AskCoachUseCase]（问答）与 [CoachInsightUseCase]（进度解读）复用，避免两处重复取数逻辑。
 * 所有取数都在调用方协程内（本用例自身不切线程；调用方已在 IO 上下文里）。
 *
 * @param windowDays 统计窗口（近 N 天），`< 1` 会被钳到 1
 */
class BuildCoachContextUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val planRepository: PlanRepository,
    private val exerciseRepository: ExerciseRepository,
    private val checkInRepository: CheckInRepository,
    private val bodyMetricRepository: BodyMetricRepository,
    private val mealRepository: MealRepository,
    private val mealItemRepository: MealItemRepository,
    private val calculateStreak: CalculateStreakUseCase,
    private val clock: Clock,
    private val timeZone: TimeZone,
) {

    suspend operator fun invoke(windowDays: Int = DEFAULT_WINDOW_DAYS): CoachContext {
        val window: Int = windowDays.coerceAtLeast(1)
        val today: Long = DateUtils.todayEpochDay(clock, timeZone)

        val profile: UserProfile = settingsRepository.profile().first()

        // 本周计划 + 动作名（名称缺失时留空，不崩）。
        val exerciseNames: Map<Long, String> =
            exerciseRepository.observeActive().first().associate { it.id to it.name }
        // observeAll() 只给**生效**行，且含「每周相同」模板行（weekStartEpochDay = 0）。
        val planRows: List<WeekPlan> = planRepository.observeAll().first()
        val weeklyPlan: List<CoachPlanLine> = planRows
            .sortedWith(compareBy({ it.dayOfWeek }, { it.sortOrder }, { it.exerciseId }))
            .map { plan ->
                CoachPlanLine(
                    dayOfWeek = plan.dayOfWeek,
                    exerciseName = exerciseNames[plan.exerciseId].orEmpty(),
                    targetSets = plan.targetSets,
                    targetReps = plan.targetReps,
                )
            }

        // 近 N 天打卡：条数 + 平均 RPE。
        val checkIns = checkInRepository.observeBetween(today - (window - 1), today).first()
        val rpes: List<Int> = checkIns.mapNotNull { it.rpe }
        val averageRpe: Double? = if (rpes.isEmpty()) null else rpes.average()

        // 体重变化：observeByType 约定降序 → 最新两条相减。
        val weights = bodyMetricRepository.observeByType(BodyMetricType.WEIGHT).first()
        val weightDeltaKg: Float? =
            if (weights.size >= 2) weights[0].value - weights[1].value else null

        // 连续天数（全历史活跃日 + 以"今天"为基准）。
        // ⚠️ 应做日必须与今日页同一口径：固定排「周一三五」的人，休息日既不打断也不计分。
        // 这里原先硬传 null（= 每天都该打卡），于是同一份数据今日页写「连续 6 天」、
        // 教练页写「连续 0 天」，而这个 0 还会跟着 context 发给模型。
        // 取统计窗口覆盖到的那几周 + 模板行；动作已被删的行剔掉（与今日页 usable 同规则）。
        val windowStart: Long = today - (window - 1)
        val expectedWeekdays: Set<Int>? = planRows
            .filter { row ->
                row.isTemplate ||
                    row.weekStartEpochDay <= today &&
                    row.weekStartEpochDay + WEEK_DAYS - 1 >= windowStart
            }
            .filter { row -> row.exerciseId in exerciseNames }
            .map { row -> row.dayOfWeek }
            .toExpectedWeekdaysOrNull()
        val streak = calculateStreak(
            epochDays = checkInRepository.observeActiveDaysSince(0L).first(),
            expectedWeekdays = expectedWeekdays,
            asOfEpochDay = today,
        )

        // 今日饮食：**分子走实际摄入**（明细优先 → 打了勾的整餐值 → 都没有就是 0）。
        // ⚠️ 别再退回 `observeTotals().intakeKcal`：那个数是"打了勾的那一餐的整餐值"，
        // 而勾可能只是完成计划的标记 —— 发给模型就等于告诉它"今天吃了 2200 kcal"，
        // 而用户其实一口都没记。教练会照着这个没发生过的数字提建议。
        val activeMeals = mealRepository.getMealsIncludingInactive(today).filter { it.isActive }
        val intake = MealIntakeCalculator.compute(
            meals = activeMeals,
            items = mealItemRepository.getByDate(today),
        )
        val planKcal: Int = activeMeals.sumOf { it.kcal }

        return CoachContext(
            profile = profile,
            weeklyPlan = weeklyPlan,
            checkInCount = checkIns.size,
            windowDays = window,
            averageRpe = averageRpe,
            weightDeltaKg = weightDeltaKg,
            currentStreak = streak.current,
            todayIntakeKcal = intake.kcal,
            todayPlanKcal = planKcal,
        )
    }

    companion object {
        /** 默认统计窗口：近 7 天（问答用；进度解读用 14，见 [CoachInsightUseCase.WINDOW_DAYS]）。 */
        const val DEFAULT_WINDOW_DAYS: Int = 7

        /** 一周 7 天（判断某周是否落进统计窗口用）。 */
        private const val WEEK_DAYS: Int = 7
    }
}
