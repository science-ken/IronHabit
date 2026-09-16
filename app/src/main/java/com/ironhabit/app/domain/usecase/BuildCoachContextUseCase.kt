package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.util.DateUtils
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
 * @property todayIntakeKcal 今日已摄入热量（只算已完成餐）
 * @property todayPlanKcal 今日计划总热量（全部餐）
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
        val weeklyPlan: List<CoachPlanLine> = planRepository.observeAll().first()
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
        val streak = calculateStreak(
            epochDays = checkInRepository.observeActiveDaysSince(0L).first(),
            expectedWeekdays = null,
            asOfEpochDay = today,
        )

        // 今日饮食合计（已摄入 / 计划）。
        val totals = mealRepository.observeTotals(today).first()

        return CoachContext(
            profile = profile,
            weeklyPlan = weeklyPlan,
            checkInCount = checkIns.size,
            windowDays = window,
            averageRpe = averageRpe,
            weightDeltaKg = weightDeltaKg,
            currentStreak = streak.current,
            todayIntakeKcal = totals.intakeKcal,
            todayPlanKcal = totals.planKcal,
        )
    }

    companion object {
        /** 默认统计窗口：近 7 天（问答用；进度解读用 14，见 [CoachInsightUseCase.WINDOW_DAYS]）。 */
        const val DEFAULT_WINDOW_DAYS: Int = 7
    }
}
