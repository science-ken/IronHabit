package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.domain.util.TodayClock
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 「这一天算不算训练日」的**唯一判据**（热量分支只认这里）。
 *
 * ## 为什么要有它
 * 此前 `GetTodayMealsUseCase` 与 `GenerateDietPlanUseCase` 各自写了一遍
 * `planRepository.observePlansForDay(weekday).isNotEmpty()` —— 把"**排了**计划"当成"**练了**"。
 * 后果是排了却没练的日子仍按训练日给热量，长期偏高；两处各写一遍，改一处另一处不动，
 * 就是"同一个业务问题两个真相源"的典型形状。
 *
 * ## 三条规则（按优先级）
 * 1. 当天**有实际打卡** → 训练日；
 * 2. 当天没打卡，但那天是**今天或未来**且排了计划 → 训练日。
 *    这条回落是必需的：早上生成当天饮食时还没有任何打卡，只看打卡会把今天要练的那次训练抹掉，
 *    用户练完发现热量目标比平时还低；
 * 3. 当天没打卡且那天**已是过去** → 休息日。那天到底没练，按训练日给热量就是虚高。
 *
 * ## 顺带修掉的跨周 bug
 * 旧写法用的是 `observePlansForDay(weekday)`（无周参数），它内部固定取**当前周**。
 * 于是查看过去/未来某一天时，"那天是不是训练日"是按**本周**的排课判的 —— 翻到下周尤其明显。
 * 本类显式按 `epochDay` 所在周取计划（[PlanRepository.observeEffectivePlanForDay]，
 * 与今日页清单同一判据：专属行优先、回落「每周相同」）。
 */
class TrainingDayResolver @Inject constructor(
    private val checkInRepository: CheckInRepository,
    private val planRepository: PlanRepository,
    private val todayClock: TodayClock,
) {

    /** 响应式版本：今日页/饮食区用它，写库之后目标热量会自己重算。 */
    fun observe(epochDay: Long): Flow<Boolean> {
        val weekday: Int = DateUtils.weekdayMon1(epochDay)
        val weekStart: Long = DateUtils.weekStartMon1(epochDay)
        return combine(
            checkInRepository.observeByDate(epochDay).map { checkIns -> checkIns.isNotEmpty() },
            planRepository.observeEffectivePlanForDay(weekday, weekStart).map { plans -> plans.isNotEmpty() },
            // 「今天或未来」= 那天还没过完，计划态仍然有效。跟着 TodayClock 而不是构造时快照。
            todayClock.epochDay.map { today -> epochDay >= today },
        ) { checkedIn, planned, isTodayOrFuture ->
            checkedIn || (planned && isTodayOrFuture)
        }
    }

    /** 一次性版本：生成饮食计划这类 suspend 路径用。 */
    suspend operator fun invoke(epochDay: Long): Boolean = observe(epochDay).first()
}
