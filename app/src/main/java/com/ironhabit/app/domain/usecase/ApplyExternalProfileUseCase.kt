package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ProfileFieldDiff
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * 把用户**勾选过的那几条**档案差异写进档案。
 *
 * ## 为什么没有"整体保存"
 * 档案没有 `saveProfile()` 这样的方法 —— [SettingsRepository] 是逐字段 setter
 * （`setProfileGoal` / `setProfileEquipment` / …）。这不是遗憾，是这条通道的安全边界：
 * 只写勾了的那几项，其余字段连读都不读，"导入顺手把身高改了"在类型上就不可表达。
 *
 * ## 三条已确认的性质
 * 1. **零连带**：档案存在 DataStore 而非 Room，写它不会改写任何已入库的计划/打卡行，
 *    只会被**以后**的生成读到的那一次生效；
 * 2. **越界值到不了这里**：[ProfileFieldDiff] 由
 *    [com.ironhabit.app.domain.ai.external.ExternalProfilePatch.diffsAgainst] 产出，
 *    那一步已经按 `ProfileLimits` 钳制过，仓库层还会再钳一次（两道同一函数）；
 * 3. **不给撤销**：与"采纳这天"同一条时机口径 —— 点一下就写，改错了去「我的」页再改回来。
 *    所以这里没有事务、也没有回滚快照。
 */
class ApplyExternalProfileUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {

    /** @return 实际写入的字段条数（空表 = 什么都没写，返回 0） */
    suspend operator fun invoke(diffs: List<ProfileFieldDiff>): Int {
        diffs.forEach { diff ->
            // 穷尽匹配、不写 else：加了新档案字段而这里没处理，要编译不过。
            when (diff) {
                is ProfileFieldDiff.GoalChange -> settingsRepository.setProfileGoal(diff.to)
                is ProfileFieldDiff.WeightChange -> settingsRepository.setProfileGoalWeightKg(diff.to)
                is ProfileFieldDiff.DaysChange -> settingsRepository.setProfileTrainingDaysPerWeek(diff.to)
                is ProfileFieldDiff.EquipmentChange -> settingsRepository.setProfileEquipment(diff.to)
                is ProfileFieldDiff.InjuryAreaChange -> settingsRepository.setProfileInjuryAreas(diff.to)
                // 空串 = 清空备注（仓库侧约定：空白与 null 同义）。
                is ProfileFieldDiff.InjuryNoteChange -> settingsRepository.setProfileInjuryNote(
                    diff.to.takeIf { it.isNotBlank() },
                )
            }
        }
        return diffs.size
    }
}
