package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.CheckInRepository
import javax.inject.Inject

/**
 * 撤销打卡用例。
 *
 * 删除某动作某天的打卡记录；删除后 `check_ins` 的 `Flow` 会重发射，
 * streak 与进度环自动重算（架构 §4.2「撤销归零」边界）。
 */
class UndoCheckInUseCase @Inject constructor(
    private val checkInRepository: CheckInRepository,
) {

    suspend operator fun invoke(exerciseId: Long, epochDay: Long) {
        checkInRepository.delete(exerciseId, epochDay)
    }
}
