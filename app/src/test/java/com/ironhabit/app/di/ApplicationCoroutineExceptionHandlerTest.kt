package com.ironhabit.app.di

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用级协程兜底（[applicationCoroutineExceptionHandler]）的判据。
 *
 * 存在的理由是一条已经写坏过的假设：`SupervisorJob` 常被当成"这样就不会崩"，
 * 其实它只隔离兄弟协程，未捕获异常照样交给线程的 UncaughtExceptionHandler 打死进程。
 * `BootReceiver` / `ReminderReceiver` 都在这个 scope 里 `launch`，且由系统调起 ——
 * 崩了的表现是"每次开机崩一次""闹钟把 App 叫起来又崩掉"，两条都不该留给线上发现。
 */
class ApplicationCoroutineExceptionHandlerTest {

    /** 用一个可变列表当"上报出口"，正好证明 handler 是可注入的（生产注 `Log.e`）。 */
    private fun scopeWith(reported: MutableList<Pair<String, Throwable>>) = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Unconfined +
            applicationCoroutineExceptionHandler { message, throwable -> reported += message to throwable },
    )

    /** 在块内接管线程默认 UEH，返回"外溢了几次"—— 外溢一次就等于真机上崩一次进程。 */
    private fun escapesToThread(block: () -> Unit): Int {
        val escapes: AtomicInteger = AtomicInteger()
        val saved: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> escapes.incrementAndGet() }
        try {
            block()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(saved)
        }
        return escapes.get()
    }

    @Test
    fun uncaughtExceptionIsReportedAndNeverReachesTheThread() {
        val reported: MutableList<Pair<String, Throwable>> = mutableListOf()
        val scope = scopeWith(reported)
        val boom = IllegalStateException("模拟 SQLite IOException")

        val escapes: Int = escapesToThread { scope.launch { throw boom } }

        assertEquals(
            "handler 接住后不该再交给线程的 UncaughtExceptionHandler（交出去=真机崩进程）",
            0,
            escapes,
        )
        assertEquals(listOf(APPLICATION_SCOPE_UNCAUGHT to boom), reported)
    }

    @Test
    fun aFailingCoroutineDoesNotCancelItsSiblings() {
        val reported: MutableList<Pair<String, Throwable>> = mutableListOf()
        val scope = scopeWith(reported)
        var siblingRan = false

        scope.launch { throw IllegalStateException("第一条失败") }
        scope.launch { siblingRan = true }

        assertTrue("SupervisorJob 的那一半职责仍然要在：兄弟协程不能被连带取消", siblingRan)
        assertEquals(1, reported.size)
    }

    /**
     * 钉住一条**实测**语义：kotlinx-coroutines 在这个版本里把 `Error` 也交给 handler，
     * 进程不会因此崩。写成断言而不是注释，是因为这是库的内部行为 ——
     * 哪天升级协程库改了这个语义，这里会红，那时才需要回头给接收器加更硬的兜底。
     */
    @Test
    fun errorIsAlsoCaughtByTheHandler() {
        val reported: MutableList<Pair<String, Throwable>> = mutableListOf()
        val scope = scopeWith(reported)

        val escapes: Int = escapesToThread { scope.launch { throw AssertionError("模拟 Error 级故障") } }

        assertEquals("实测 handler 连 Error 一起接住；红了说明协程库语义变了，接收器的兜底要重估", 0, escapes)
        assertEquals(1, reported.size)
    }

    /**
     * 上面三条各自 new 了一个 scope，所以它们**证明不了生产那条 scope 挂着 handler** ——
     * 把 `+ applicationCoroutineExceptionHandler { … }` 从 `provideApplicationScope` 里摘掉，
     * 前三条照样全绿，而真机上"开机崩一次"就回来了。这一条守的正是那一格。
     *
     * 形状照 `ui/InputLimitsCallSiteContractTest`：JVM 里起不了 Hilt 图，就用扫描式断言，
     * 成本低，但它挡的是"以后有人觉得多余删掉"这一种真实的腐烂方式。
     */
    @Test
    fun productionApplicationScopeIsBuiltWithTheHandler() {
        val src: String = File(mainSourceRoot(), "di/AppModule.kt").readText()
        val provider: String = src
            .substringAfter("fun provideApplicationScope(")
            .substringBefore("@Provides", "「整文件读完了」")

        assertTrue(
            "provideApplicationScope 的构造式里没有 applicationCoroutineExceptionHandler —— " +
                "摘掉它 = 接收器里一次异常就带走进程：\n$provider",
            "applicationCoroutineExceptionHandler" in provider,
        )
    }

    /** 与 `InputLimitsCallSiteContractTest` 同一套定位：工作目录可能是 `app/`，也可能是仓库根。 */
    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf(
                "src/main/java/com/ironhabit/app",
                "app/src/main/java/com/ironhabit/app",
            ).map { File(dir, it) }.firstOrNull { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("找不到主源码根目录（user.dir=${System.getProperty("user.dir")}）")
    }
}
