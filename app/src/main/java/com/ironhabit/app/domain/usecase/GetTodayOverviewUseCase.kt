package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.domain.model.HabitLog
import com.ironhabit.app.domain.model.TodayOverview
import com.ironhabit.app.domain.model.TodayPlanItem
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.util.DateUtils
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 组装「今日」聚合视图（架构 §3.2 / §4.2）。
 *
 * 组合 4 个仓库 + streak 计算：计划 / 今日打卡 / 动作 / 活跃日 / 习惯 / 习惯日志，
 * 全部是 Room `Flow`，写入后自动重发射，UI 无需手动刷新。
 *
 * @return 响应式 [TodayOverview]
 */
class GetTodayOverviewUseCase @Inject constructor(
    private val planRepository: PlanRepository,
    private val checkInRepository: CheckInRepository,
    private val habitRepository: HabitRepository,
    private val exerciseRepository: ExerciseRepository,
    private val calculateStreakUseCase: CalculateStreakUseCase,
) {

    operator fun invoke(epochDay: Long): Flow<TodayOverview> {
        val weekday: Int = DateUtils.weekdayMon1(epochDay)
        // P3：计划按周存放 —— 看哪天就取**那一天所在周**的计划。
        // 翻到下一周且那一周还没排计划时，这里会拿到空列表 → 界面显示「创建训练计划」。
        val weekStart: Long = DateUtils.weekStartMon1(epochDay)

        val plansFlow = planRepository.observeEffectivePlanForDay(weekday, weekStart)
        val todayCheckInsFlow = checkInRepository.observeByDate(epochDay)
        val exercisesFlow = exerciseRepository.observeActive()
        val activeDaysFlow = checkInRepository.observeActiveDaysSince(SINCE_EPOCH_DAY)
        val habitsFlow = habitRepository.observeActiveHabits()
        val habitLogsFlow = habitRepository.observeLogsBetween(SINCE_EPOCH_DAY, epochDay)
        // 「应做日」来源：**那一周**已排计划里的星期（休息日不打断连续训练记录）。
        val plannedWeekdaysFlow = planRepository.observeEffectivePlanForWeek(weekStart)
            .map { plans -> plans.map { plan -> plan.dayOfWeek }.distinct().sorted() }

        val coreFlow: Flow<OverviewCore> = combine(
            plansFlow,
            todayCheckInsFlow,
            exercisesFlow,
            activeDaysFlow,
            habitsFlow,
        ) { plans, todayCheckIns, exercises, activeDays, habits ->
            OverviewCore(
                epochDay = epochDay,
                plans = plans,
                todayCheckIns = todayCheckIns,
                exercises = exercises,
                activeDays = activeDays,
                habits = habits,
            )
        }

        val withWeekdaysFlow: Flow<OverviewCore> =
            combine(coreFlow, plannedWeekdaysFlow) { core, weekdays ->
                core.copy(plannedWeekdays = weekdays)
            }

        return combine(withWeekdaysFlow, habitLogsFlow) { core, habitLogs ->
            buildOverview(core, habitLogs)
        }
    }

    private fun buildOverview(core: OverviewCore, habitLogs: List<HabitLog>): TodayOverview {
        val exerciseById: Map<Long, Exercise> = core.exercises.associateBy { it.id }
        val checkInByExercise: Map<Long, CheckIn> = core.todayCheckIns.associateBy { it.exerciseId }

        val planItems: List<TodayPlanItem> = core.plans.mapNotNull { plan: WeekPlan ->
            val exercise = exerciseById[plan.exerciseId] ?: return@mapNotNull null
            val checkIn = checkInByExercise[plan.exerciseId]
            TodayPlanItem(
                plan = plan,
                exercise = exercise,
                isCompleted = checkIn != null,
                checkIn = checkIn,
            )
        }

        val completedLogsByHabit: Map<Long, List<HabitLog>> =
            habitLogs.filter { it.isCompleted }.groupBy { it.habitId }

        val habitItems: List<HabitItem> = core.habits.map { habit: Habit ->
            val logs = completedLogsByHabit[habit.id].orEmpty()
            val doneDays = logs.map { it.dateEpochDay }.distinct().sortedDescending()
            val completedToday = logs.any { it.dateEpochDay == core.epochDay }
            HabitItem(
                habit = habit,
                isCompletedToday = completedToday,
                // 「每周几」习惯按自己的排期计连续；每日习惯传 null（每天都应做）。
                // 修复 C5：口径基准 = **所选日**（游标日），而非"真实今天"。
                streak = calculateStreakUseCase(
                    epochDays = doneDays,
                    expectedWeekdays = habit.expectedWeekdays,
                    asOfEpochDay = core.epochDay,
                ),
            )
        }

        val completedCount = planItems.count { it.isCompleted } + habitItems.count { it.isCompletedToday }
        val totalCount = planItems.size + habitItems.size

        return TodayOverview(
            dateEpochDay = core.epochDay,
            plans = planItems,
            habits = habitItems,
            completedCount = completedCount,
            totalCount = totalCount,
            // 训练连续天数按「已排计划的星期」计应做日：周一三五的计划不再因休息日断档。
            // 没有任何计划时传 null → 退回「每天都算」的旧口径，避免把无计划用户清零。
            // 修复 C5：同样以**所选日**为基准，避免"游标日"与"真实今天"口径混用（切换日期时 streak 不一致）。
            trainingStreak = calculateStreakUseCase(
                epochDays = core.activeDays,
                expectedWeekdays = core.plannedWeekdays.toExpectedWeekdaysOrNull(),
                asOfEpochDay = core.epochDay,
            ),
        )
    }

    /** 中间聚合体（避免 `combine` 参数过多）。 */
    private data class OverviewCore(
        val epochDay: Long,
        val plans: List<WeekPlan>,
        val todayCheckIns: List<CheckIn>,
        val exercises: List<Exercise>,
        val activeDays: List<Long>,
        val habits: List<Habit>,
        /** 已排计划的星期（`1..7`，升序）；空 = 无计划。 */
        val plannedWeekdays: List<Int> = emptyList(),
    )

    private companion object {
        /** 取「全历史」活跃日（自 1970-01-01 起），用于 streak 的 best 计算。 */
        const val SINCE_EPOCH_DAY: Long = 0L
    }
}

/**
 * 已排计划星期 → 应做星期集合；无计划（空）返回 `null`（= 每天都算，保持旧口径）。
 *
 * 无计划时返回 `null` 是刻意的：否则「只打卡、没排计划」的用户连续天数会被清零。
 */
private fun List<Int>.toExpectedWeekdaysOrNull(): Set<Int>? =
    filter { it in 1..WEEK_DAYS_OF_WEEK }.toSet().takeIf { it.isNotEmpty() }

/** 一周 7 天。 */
private const val WEEK_DAYS_OF_WEEK: Int = 7
