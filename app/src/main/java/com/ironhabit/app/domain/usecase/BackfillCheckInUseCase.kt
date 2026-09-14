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
        val checkIn = CheckIn(
            id = 0L,
            exerciseId = exerciseId,
            planId = null,
            dateEpochDay = epochDay,
            dateStartMillis = DateUtils.startOfDayMillis(epochDay, timeZone),
            completedSets = sets,
            completedReps = reps,
            weightKg = null,
            durationMinutes = null,
            notes = null,
            isQuick = false,
            loggedAtMillis = nowMillis,
            createdAt = nowMillis,
        )
        checkInRepository.upsert(checkIn)
    }
}
