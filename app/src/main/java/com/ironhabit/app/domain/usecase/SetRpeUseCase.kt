package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.CheckInRepository
import javax.inject.Inject

/**
 * 「写入 / 清除 RPE」用例。
 *
 * RPE（主观强度 `1..10`）是渐进超负荷算法的唯一输入源；越界值在 data 层被钳制到合法区间。
 *
 * @param exerciseId 动作 id
 * @param epochDay 打卡日期（`LocalDate.toEpochDays()`）
 * @param rpe 强度 `1..10`；传 `null` 表示清除
 */
class SetRpeUseCase @Inject constructor(
    private val checkInRepository: CheckInRepository,
) {

    suspend operator fun invoke(exerciseId: Long, epochDay: Long, rpe: Int?) {
        checkInRepository.setRpe(exerciseId, epochDay, rpe)
    }
}
