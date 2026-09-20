package com.ironhabit.app.domain.util

import com.ironhabit.app.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 全 App 的「今天」唯一来源。
 *
 * ## 为什么要有它（这是一个架构级缺失，不是某个页面的笔误）
 * 在此之前 `private fun todayEpochDay()` 在项目里被抄了 **6 份**
 * （`TodayViewModel` / `TrainViewModel` / `DisciplineViewModel` / `HistoryViewModel` /
 * `AiCoachViewModel` / `StatsRepositoryImpl`）。每份"调用时实时读 clock"本身都没错，
 * 错在**每个调用方自己决定什么时候读**，于是出现两类固化：
 * - `TrainViewModel` 把结果存进 `private val currentWeekStart`（构造时算一次）→
 *   App 常驻开着跨过周一 00:00，从动作库「+」排进去的动作**落进上一周**；
 * - `TodayViewModel` 把它当日期游标的**初值** → 跨过 00:00 后页面标题仍写「今日」，
 *   游标却是昨天，一指点下去**打卡写进昨天**，streak 与热力图一起被污染。
 *
 * 只修其中一处必然留下另一处，所以把"今天"收成一个可订阅的单例。
 *
 * ## 两条使用约定
 * 1. **写库路径**用同步取值（[todayEpochDay] / [weekStartEpochDay] / [todayWeekday]）：
 *    取的是手指按下去那一刻，不是对象构造那一刻；
 * 2. **界面与数据流**订阅 [epochDay]：常驻前台也能在换天时自己刷新（30s 一轮，
 *    只有真的换天才发射，不会每分钟吵醒一次数据库）。
 *
 * ## 时区口径
 * 走注入的 [TimeZone]，因此本单例在进程存活期间锁定一个时区。
 * 刻意**不**每次读 `TimeZone.currentSystemDefault()`：那样 JVM 测不了。
 * [com.ironhabit.app.di.AppModule.provideTimeZone] 那边（B-5）解决的是"非单例消费者
 * 每次注入重新求值"，与本类正交；进程不重启而用户跨时区旅行属于提醒调度的问题域，
 * 见 `ReminderSchedulerImpl`。
 */
@Singleton
class TodayClock @Inject constructor(
    private val clock: Clock,
    private val timeZone: TimeZone,
    @ApplicationScope scope: CoroutineScope,
) {

    private val _epochDay = MutableStateFlow(readToday())

    /** 今天的 epochDay，换天时自动发射。 */
    val epochDay: StateFlow<Long> = _epochDay.asStateFlow()

    init {
        scope.launch {
            while (true) {
                delay(TICK_MS)
                refresh()
            }
        }
    }

    /**
     * 重读一次时钟，换天了才发射。
     *
     * 抽成公开函数有两个用处：轮询循环只剩"到点调用它"这一行；
     * 以及将来接 `ACTION_DATE_CHANGED` 广播或 `ON_RESUME` 钩子时复用同一个入口，
     * 不必再复制一份比较逻辑。
     */
    fun refresh(): Long {
        val today: Long = readToday()
        if (today != _epochDay.value) _epochDay.value = today
        return today
    }

    /** 同步取当下。**写库路径用这个**，不要用 [epochDay] 的缓存值。 */
    fun todayEpochDay(): Long = readToday()

    /** 今天所在周的周一（P3：计划按周存放，落库必须带这个）。 */
    fun weekStartEpochDay(): Long = DateUtils.weekStartMon1(todayEpochDay())

    /** 今天的星期：`1` = 周一 … `7` = 周日。 */
    fun todayWeekday(): Int = DateUtils.weekdayMon1(todayEpochDay())

    private fun readToday(): Long = DateUtils.todayEpochDay(clock, timeZone)

    private companion object {
        /** 轮询间隔：30 秒。换天最迟晚 30 秒被感知，而写库路径不受这个延迟影响。 */
        const val TICK_MS: Long = 30_000L
    }
}
