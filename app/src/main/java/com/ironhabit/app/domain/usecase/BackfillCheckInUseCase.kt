package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.util.DateUtils
import javax.inject.Inject
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 补卡用例（对历史日期补录一条训练记录）。
 *
 * 与一键打卡共用 upsert（`isQuick = false`），因此补卡后日期序列可能变连续，
 * streak 会自然「回升」（架构 §4.2「补卡后回升」边界）。
 *
 * 注：补卡为历史录入，**不**累加动作 `timesUsed`（避免污染动作库排序）。
 */
class BackfillCheckInUseCase @Inject constructor(
    private val checkInRepository: CheckInRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
) {

    suspend operator fun invoke(exerciseId: Long, epochDay: Long, sets: Int, reps: Int) {
        val nowMillis = clock.now().toEpochMilliseconds()
        // 与详细打卡同理：该日已有勾选时保留「是哪几组」的身份，只按数量增减调整位图。
        val previous: CheckIn? = checkInRepository.getForExerciseOnDate(exerciseId, epochDay)
        val previousMask: Int = previous?.completedSetsMask ?: 0
        val checkIn = CheckIn(
            id = 0L,
            exerciseId = exerciseId,
            planId = null,
            dateEpochDay = epochDay,
            dateStartMillis = DateUtils.startOfDayMillis(epochDay, timeZone),
            completedSetsMask = CheckIn.mergedMask(count = sets, previousMask = previousMask),
            completedReps = reps,
            // 补卡弹层里**只有组×次**，没有重量/时长/备注这三格。原先写死 null，
            // 于是一天已有的"实重 90kg / 备注"会被一次补卡抹平（审查报告 P2-2 同族）：
            // 弹层没提供的格子沿用旧行，才是"只改组次"这个动作的真实语义。
            // 新行（previous == null）照旧是 null —— 那不是"清空"，那是"本来就没有"。
            weightKg = previous?.weightKg,
            durationMinutes = previous?.durationMinutes,
            notes = previous?.notes,
            isQuick = false,
            loggedAtMillis = nowMillis,
            createdAt = nowMillis,
        )
        checkInRepository.upsert(checkIn)
    }
}
