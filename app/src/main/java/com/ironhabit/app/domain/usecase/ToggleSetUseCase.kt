package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.CheckInRepository
import javax.inject.Inject

/**
 * 「勾选 / 取消某一组」用例（0-based）。
 *
 * 维护 [com.ironhabit.app.domain.model.CheckIn.completedSetsMask]（唯一真源）与派生列
 * `completed_sets` 的同步写入，保证不变量 `completed_sets == mask.countOneBits()`。
 *
 * **越界安全**：`setIndex` 超出 `0 until MAX_SETS` 时静默忽略（绝不用 `require()` 抛异常），
 * 因为真机上抛异常等于崩溃（schema-v2 §2.3）。
 *
 * @param exerciseId 动作 id
 * @param epochDay 打卡日期（`LocalDate.toEpochDays()`）
 * @param setIndex 组序号，`0` = 第 1 组
 */
class ToggleSetUseCase @Inject constructor(
    private val checkInRepository: CheckInRepository,
) {

    suspend operator fun invoke(exerciseId: Long, epochDay: Long, setIndex: Int) {
        checkInRepository.toggleSet(exerciseId, epochDay, setIndex)
    }
}
