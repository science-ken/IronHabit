package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.TodayPlanItem
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.util.DateUtils
import javax.inject.Inject
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 「一键打卡」用例（架构 §4.1）。
 *
 * 以计划目标值直接 upsert 今日打卡，并累加动作使用次数。
 * `CheckInDao` 的唯一约束 `(exercise_id, date_epoch_day)` 保证重复点击**幂等**、不产生脏数据。
 */
class QuickCheckInUseCase @Inject constructor(
    private val checkInRepository: CheckInRepository,
    private val exerciseRepository: ExerciseRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
) {

    /** 由计划条目直接打卡（T04 传入 `WeekPlan`）。 */
    suspend operator fun invoke(plan: WeekPlan) {
        val today = DateUtils.todayEpochDay(clock, timeZone)
        val nowMillis = clock.now().toEpochMilliseconds()
        val checkIn = CheckIn(
            id = 0L,
            exerciseId = plan.exerciseId,
            planId = plan.id.takeIf { it > 0L },
            dateEpochDay = today,
            dateStartMillis = DateUtils.startOfDayMillis(today, timeZone),
            completedSetsMask = CheckIn.maskFromCount(plan.targetSets),
            completedReps = plan.targetReps,
            weightKg = plan.targetWeightKg,
            durationMinutes = plan.targetDurationMin,
            notes = null,
            isQuick = true,
            loggedAtMillis = nowMillis,
            createdAt = nowMillis,
        )
        checkInRepository.upsert(checkIn)
        exerciseRepository.bumpUsage(plan.exerciseId)
    }

    /** 便捷重载：直接传今日列表项。 */
    suspend operator fun invoke(item: TodayPlanItem) = invoke(item.plan)
}
