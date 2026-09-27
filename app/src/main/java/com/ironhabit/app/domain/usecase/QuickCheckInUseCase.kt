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
        checkInRepository.upsert(
            keepingLoggedDetails(checkIn, checkInRepository.getForExerciseOnDate(plan.exerciseId, epochDay)),
        )
        exerciseRepository.bumpUsage(plan.exerciseId)
    }

    /** 便捷重载：直接传列表项 + 所选日。 */
    suspend operator fun invoke(item: TodayPlanItem, epochDay: Long) = invoke(item.plan, epochDay)

    companion object {

        /**
         * **「用户手记的那一行，不被计划目标值改写」**（审查报告 P2-2）。
         *
         * 一键打卡按钮在"部分完成"的条目上仍然可点（`targetSets` 没勾满是常态），
         * 而它构造的是**计划目标值**：`completedReps = plan.targetReps`、
         * `weightKg = plan.targetWeightKg`、`notes = null`。原先整行覆盖上去，
         * 用户先详细打卡记下"实际 90kg、备注腰有点顶"，再顺手点一下按钮，
         * 备注就没了、实际重量被换成计划值 —— 而这正是唯一的真实数据来源。
         *
         * 只保"用户手记过"的行（`isQuick = false`）：两条都是快速打卡时（旧行 `isQuick = true`）
         * 让新的目标值刷过去是对的（改了计划重量再点一次，理应跟着计划走）。
         *
         * `isQuick` 必须原样留住：它是"这行的重量是实际值还是计划复读"的**唯一判据**，
         * 翻成 true 等于把真数据降级 —— 趋势图和渐进超负荷提示都会开始说谎。
         */
        private fun keepingLoggedDetails(incoming: CheckIn, existing: CheckIn?): CheckIn {
            if (existing == null || existing.isQuick) return incoming
            return incoming.copy(
                completedReps = existing.completedReps,
                weightKg = existing.weightKg,
                durationMinutes = existing.durationMinutes,
                notes = existing.notes,
                rpe = existing.rpe,
                isQuick = existing.isQuick,
            )
        }
    }
}
