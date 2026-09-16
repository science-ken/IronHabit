package com.ironhabit.app.di

import javax.inject.Qualifier

/**
 * Hilt 自定义限定符。
 *
 * 目的：让 Dispatcher / CoroutineScope 一律通过构造器注入，避免代码中硬编码 `Dispatchers.IO`，
 * 从而在 JVM 单测时可替换为 `UnconfinedTestDispatcher`。
 */

/** 标注 IO 密集型调度器（数据库读写、文件导入导出等）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** 标注 CPU 密集型调度器（streak/统计等纯计算）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** 标注与应用同生命周期的协程作用域（用于 Application 级后台任务）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * 标注应用版本名（`BuildConfig.VERSION_NAME`，如 `"2.0.1"`）。
 *
 * 目的：data 层（备份文件的 `appVersion`）不直接引用 `BuildConfig`，从而保持纯 JVM 可测。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppVersion
