package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.util.DateUtils
import javax.inject.Inject
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 详细打卡输入（组 / 次 / 重量 / 时长 / 备注）。
 *
 * @property exerciseId 动作 id
 * @property planId 来源计划 id，可空（手动补录时无计划）
 * @property dateEpochDay 打卡日期（默认应为今天；补录可传历史日）
 */
data class DetailedCheckInInput(
    val exerciseId: Long,
    val planId: Long? = null,
    val dateEpochDay: Long,
    val completedSets: Int,
    val completedReps: Int,
    val weightKg: Float? = null,
    val durationMinutes: Int? = null,
    val notes: String? = null,
)

/**
 * 「详细打卡 / 补录」用例。
 *
 * 与 [QuickCheckInUseCase] 共用同一张表与唯一约束，写 `isQuick = false` 并记录实际组次重量。
 * 补录历史日同样走 upsert（天然融入 streak 计算，见架构 §4.2）。
 */
class DetailedCheckInUseCase @Inject constructor(
    private val checkInRepository: CheckInRepository,
    private val exerciseRepository: ExerciseRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
) {

    /** 主入口：传入完整输入模型。 */
    suspend operator fun invoke(input: DetailedCheckInInput) {
        upsert(
            exerciseId = input.exerciseId,
            planId = input.planId,
            epochDay = input.dateEpochDay,
            sets = input.completedSets,
            reps = input.completedReps,
            weightKg = input.weightKg,
            durationMinutes = input.durationMinutes,
            notes = input.notes,
        )
    }

    /** 便捷重载：直接传原始参数（T04 弹层可用）。 */
    suspend operator fun invoke(
        exerciseId: Long,
        planId: Long?,
        epochDay: Long,
        sets: Int,
        reps: Int,
        weightKg: Float? = null,
        durationMinutes: Int? = null,
        notes: String? = null,
    ) {
        upsert(exerciseId, planId, epochDay, sets, reps, weightKg, durationMinutes, notes)
    }

    private suspend fun upsert(
        exerciseId: Long,
        planId: Long?,
        epochDay: Long,
        sets: Int,
        reps: Int,
        weightKg: Float?,
        durationMinutes: Int?,
        notes: String?,
    ) {
        val nowMillis = clock.now().toEpochMilliseconds()
        val checkIn = CheckIn(
            id = 0L,
            exerciseId = exerciseId,
            planId = planId?.takeIf { it > 0L },
            dateEpochDay = epochDay,
            dateStartMillis = DateUtils.startOfDayMillis(epochDay, timeZone),
            completedSets = sets,
            completedReps = reps,
            weightKg = weightKg,
            durationMinutes = durationMinutes,
            notes = notes,
            isQuick = false,
            loggedAtMillis = nowMillis,
            createdAt = nowMillis,
        )
        checkInRepository.upsert(checkIn)
        exerciseRepository.bumpUsage(exerciseId)
    }
}
