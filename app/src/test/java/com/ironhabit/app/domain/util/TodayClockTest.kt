package com.ironhabit.app.domain.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TodayClock] 单测：把"App 常驻前台跨午夜"这个真机很难复现的场景，用可推进的假时钟钉死。
 *
 * 两条踩过的坑，写在这里防止后人再踩：
 * 1. **不要给 [TodayClock] 传 `TestScope`** —— 它的轮询是无限循环，一旦成为测试作用域的子任务，
 *    `runTest` 会一直等它结束，最后四条用例全抛 `UncompletedCoroutinesError`。
 *    所以统一用 [clockScope]：同一个 `testScheduler`，但不是子级。
 * 2. **不要靠 `advanceTimeBy` 推那条无限循环来验证换天** —— 直接调 [TodayClock.refresh]，
 *    它就是轮询体本身，行为等价且完全确定（上一版这么写过，测试进程直接挂住）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayClockTest {

    private val utc = TimeZone.UTC

    /** 可推进的假时钟：测试里改 [instant] 就等于"过了午夜"。 */
    private inner class MutableClock(instant: Instant) : Clock {
        var instant: Instant = instant
        override fun now(): Instant = this.instant
    }

    private fun day(y: Int, m: Int, d: Int): Long = LocalDate(y, m, d).toEpochDays().toLong()

    /** 某天某时刻。`Instant + Long` 不存在（要 `Duration`），所以统一从毫秒拼。 */
    private fun at(y: Int, m: Int, d: Int, hour: Int = 0, minute: Int = 0): Instant =
        Instant.fromEpochMilliseconds(
            LocalDate(y, m, d).atStartOfDayIn(utc).toEpochMilliseconds() +
                hour * 3_600_000L + minute * 60_000L,
        )

    /**
     * 给 [TodayClock] 一个**真实调度器**上的独立作用域。
     *
     * 两个坑都踩过，别改回 TestScope / `StandardTestDispatcher`：
     * 1. 传 `TestScope` → 轮询成为测试作用域的子任务，`runTest` 一直等它，四条用例全抛
     *    `UncompletedCoroutinesError`；
     * 2. 传 `StandardTestDispatcher(testScheduler)` → 看着对了，但 `runTest` 收尾时会把测试
     *    调度器**推进到空闲**，而 `while(true) delay()` 永远不空闲，整个测试 JVM 直接挂死
     *    （表现是构建卡住不动，没有任何失败信息）。
     * 用 `Dispatchers.Default`（守护线程）后，那条循环真实存在但 `runTest` 看不见它；
     * 换天行为由下面直接调 [TodayClock.refresh] 来验证，完全确定。
     */
    private fun clockScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun refresh_publishesRolloverWithoutRecreating() = runTest {
        val clock = MutableClock(at(2026, 2, 20, hour = 22)) // 周六 22:00
        val todayClock = TodayClock(clock, utc, clockScope())

        assertEquals(day(2026, 2, 20), todayClock.epochDay.value)

        clock.instant = at(2026, 2, 21, minute = 5) // 跨到周日 00:05
        todayClock.refresh()

        assertEquals("换天必须由 refresh 发射，不能等对象重建", day(2026, 2, 21), todayClock.epochDay.value)
    }

    @Test
    fun refresh_sameDayDoesNotReEmit() = runTest {
        val clock = MutableClock(at(2026, 2, 20, hour = 1))
        val todayClock = TodayClock(clock, utc, clockScope())

        val seen = mutableListOf<Long>()
        val collector = launch { todayClock.epochDay.collect { seen.add(it) } }
        runCurrent()

        // 同一天反复刷新（轮询每 30 秒就调一次）：StateFlow 去重，只应有初始那一次。
        repeat(3) { todayClock.refresh() }
        runCurrent()
        collector.cancel()

        assertEquals(listOf(day(2026, 2, 20)), seen)
    }

    @Test
    fun syncRead_isInstantaneous() = runTest {
        // 写库路径用的同步取值：不依赖轮询，也不依赖 refresh。
        val clock = MutableClock(at(2026, 2, 20))
        val todayClock = TodayClock(clock, utc, clockScope())

        clock.instant = at(2026, 2, 23)

        assertEquals(day(2026, 2, 23), todayClock.todayEpochDay())
    }

    @Test
    fun weekStart_jumpsToNewWeekAcrossMidnightIntoMonday() = runTest {
        // 训练页那个 bug 的形状：周日深夜 App 常驻 → 周一 00:01 排课，
        // 周归属必须跟着跳到新一周，否则动作写进上一周。
        val clock = MutableClock(at(2026, 2, 22, hour = 23)) // 周日 23:00
        val todayClock = TodayClock(clock, utc, clockScope())
        assertEquals(day(2026, 2, 16), todayClock.weekStartEpochDay()) // 那一周的周一 = 2/16

        clock.instant = at(2026, 2, 23, minute = 1) // 周一 00:01

        assertEquals("同步取值必须立刻反映新一周", day(2026, 2, 23), todayClock.weekStartEpochDay())
        assertEquals(1, todayClock.todayWeekday())

        todayClock.refresh()
        assertEquals("订阅方在同一轮 refresh 后也要拿到新的一天", day(2026, 2, 23), todayClock.epochDay.value)
    }
}
