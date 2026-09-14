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

        val plansFlow = planRepository.observePlansForDay(weekday)
        val todayCheckInsFlow = checkInRepository.observeByDate(epochDay)
        val exercisesFlow = exerciseRepository.observeActive()
        val activeDaysFlow = checkInRepository.observeActiveDaysSince(SINCE_EPOCH_DAY)
        val habitsFlow = habitRepository.observeActiveHabits()
        val habitLogsFlow = habitRepository.observeLogsBetween(SINCE_EPOCH_DAY, epochDay)

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

        return combine(coreFlow, habitLogsFlow) { core, habitLogs ->
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
                streak = calculateStreakUseCase(doneDays),
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
            trainingStreak = calculateStreakUseCase(core.activeDays),
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
    )

    private companion object {
        /** 取「全历史」活跃日（自 1970-01-01 起），用于 streak 的 best 计算。 */
        const val SINCE_EPOCH_DAY: Long = 0L
    }
}
