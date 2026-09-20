package com.ironhabit.app.test

import com.ironhabit.app.domain.util.TodayClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 给 ViewModel 测试造一个 [TodayClock]。
 *
 * ⚠️ 作用域必须是**真实调度器**（`Dispatchers.Default`，守护线程），不能传 `TestScope`
 * 或 `StandardTestDispatcher(testScheduler)`：
 * - 传 TestScope → 轮询成为测试作用域的子任务，`runTest` 一直等它 → `UncompletedCoroutinesError`；
 * - 传测试调度器 → `runTest` 收尾时会把调度器"推进到空闲"，而轮询是 `while(true) delay()`，
 *   永远不空闲 → **整个测试 JVM 挂死**，连失败信息都没有（这两种都踩过）。
 *
 * 需要验证"换天"行为的测试不要靠拨虚拟时间，直接调 [TodayClock.refresh]，
 * 见 `TodayClockTest`。
 */
fun todayClockFor(clock: Clock, timeZone: TimeZone): TodayClock =
    TodayClock(clock, timeZone, CoroutineScope(SupervisorJob() + Dispatchers.Default))
