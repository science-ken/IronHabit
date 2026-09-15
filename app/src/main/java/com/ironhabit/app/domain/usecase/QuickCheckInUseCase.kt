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
 * 以计划目标值直接 upsert 打卡，并累加动作使用次数。
 * `CheckInDao` 的唯一约束 `(exercise_id, date_epoch_day)` 保证重复点击**幂等**、不产生脏数据。
 *
 * ⚠️ **写入口径（v3 统一）**：打卡日期由**调用方传入的 [epochDay]（所选日）**决定，
 * 与逐组勾选 / RPE / 撤销 / 习惯 / 补录完全一致；**不再**在用例内部自算「真实今天」，
 * 否则切到非今天查看时会在同一屏出现「一键打卡写今天、其它写所选日」两套口径。
 */
class QuickCheckInUseCase @Inject constructor(
    private val checkInRepository: CheckInRepository,
    private val exerciseRepository: ExerciseRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
) {

    /**
     * 由计划条目直接打卡。
     *
     * @param plan 来源计划条目
     * @param epochDay 打卡日期（调用方传入的**所选日**；`LocalDate.toEpochDays()`）
     */
    suspend operator fun invoke(plan: WeekPlan, epochDay: Long) {
        val nowMillis = clock.now().toEpochMilliseconds()
        val checkIn = CheckIn(
            id = 0L,
            exerciseId = plan.exerciseId,
            planId = plan.id.takeIf { it > 0L },
            dateEpochDay = epochDay,
            dateStartMillis = DateUtils.startOfDayMillis(epochDay, timeZone),
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

    /** 便捷重载：直接传列表项 + 所选日。 */
    suspend operator fun invoke(item: TodayPlanItem, epochDay: Long) = invoke(item.plan, epochDay)
}
