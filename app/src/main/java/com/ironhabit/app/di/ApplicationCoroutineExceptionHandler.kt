package com.ironhabit.app.di

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * [ApplicationScope] 的未捕获异常兜底。
 *
 * `SupervisorJob` 只隔离兄弟协程，**不拦未捕获异常**：没有这个 handler 时，
 * `applicationScope.launch` 里抛出的一切都会走到线程的 UncaughtExceptionHandler
 * 并把进程打死。最要命的是系统替我们起进程的那两条路 ——
 * [com.ironhabit.app.data.notification.BootReceiver] 收 `BOOT_COMPLETED` 后重排提醒，
 * 一次 SQLite IO 异常就变成"每次开机崩一次"。
 *
 * 上报走参数 [report] 而不是直接写 `Log`：本工程单测没有 Robolectric，
 * `android.util.Log` 在 JVM 里会抛 "Method not mocked"，把出口做成参数才能被测到。
 */
fun applicationCoroutineExceptionHandler(
    report: (message: String, throwable: Throwable) -> Unit,
): CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    report(APPLICATION_SCOPE_UNCAUGHT, throwable)
}

/** 兜底日志的固定前缀，便于在 logcat 里一眼筛出"本该崩掉进程"的那几次。 */
const val APPLICATION_SCOPE_UNCAUGHT: String = "应用级协程未捕获异常"
