package com.ironhabit.app.verify

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「包装挂起调用的通用 catch 必须重抛 [kotlinx.coroutines.CancellationException]」的
 * **契约测试**（扫描式，纯 JVM / 离线可跑；形状照 `InputLimitsCallSiteContractTest`）。
 *
 * ## 为什么需要它
 * `catch (Throwable)` / `catch (Exception)` / `runCatching` 包住挂起调用时会把取消一起吞掉：
 * VM 清理与页面切走都靠取消传播，一旦被记成"业务失败"，取消就静默失效
 * （V1 报告 P3-22 + V3 报告新-P3-2/3/4，散在十余个文件里的同一条根因）。
 * 2026-09-30 已逐点修完；本测试守的是**下一个新增的 catch**。
 *
 * ## 规则
 * `ui/screens` 之下（`ai` 子包除外，见下）与 `data/repository` 之下：
 * - `catch (…: Throwable)` / `catch (…: Exception)` 的**前 4 行**内必须出现
 *   `catch (cancellation: CancellationException)`（工程的标准写法：先接取消再接业务失败）；
 * - `runCatching` + `.onFailure { throwable ->` 的写法，其后 2 行内必须有
 *   `if (throwable is CancellationException) throw throwable`。
 *
 * ## 已知局限
 * 判据是**行级窗口**而非语法树：隔着空行/注释超过 4 行的合法写法会被误报，
 * 那就调整写法（把两个 catch 排在一起本来就是工程口径）。
 *
 * ## 范围豁免
 * - `ui/screens/ai` 子包：外部 Key AI 教练计划整体删除重做（BUGFIX_PLAN 的约束），
 *   该目录暂不纳入扫描 —— 删除完成后把过滤条件一并撤掉。
 * - `data/notification`（两个 Receiver）：跑在不可取消的 `@ApplicationScope` 上，
 *   没有取消传播语义可言。
 */
class CancellationExceptionContractTest {

    private val genericCatch = Regex("""catch \(\w+: (Throwable|Exception)\)""")

    private val ceCatch = Regex("""catch \(cancellation: CancellationException\)""")

    private val onFailureThrowable = Regex("""\.onFailure \{ throwable ->""")

    private val ceRethrow = Regex("""throwable is CancellationException""")

    /** 带理由的豁免：key = 相对 `src/main/java/com/ironhabit/app/` 的路径 + 行内容片段。 */
    private val exemptions: Map<String, String> = emptyMap()

    @Test
    fun everyGenericCatchAroundSuspendingCodeRethrowsCancellation() {
        val root = sourceRoot()
        val offenders = mutableListOf<String>()
        val scanned = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val p = file.invariantSeparatorsPath()
                ("/ui/screens/" in p && "/ui/screens/ai/" !in p) || "/data/repository/" in p
            }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                val lines = file.readLines()
                var fileHasChecks = false
                lines.forEachIndexed { index, line ->
                    val isFirstPartyCatch = genericCatch.containsMatchIn(line) &&
                        !ceCatch.containsMatchIn(line)
                    val isOnFailure = onFailureThrowable.containsMatchIn(line)
                    if (!isFirstPartyCatch && !isOnFailure) return@forEachIndexed
                    fileHasChecks = true

                    val exempt = exemptions.keys.any { key ->
                        rel in key && (key.substringAfter('|') in line || key.endsWith("|*"))
                    }
                    if (exempt) return@forEachIndexed

                    val ok = if (isOnFailure) {
                        val window = lines.drop(index + 1).take(2).joinToString("\n")
                        ceRethrow.containsMatchIn(window)
                    } else {
                        val window = lines.drop(maxOf(0, index - 4)).take(4).joinToString("\n")
                        ceCatch.containsMatchIn(window)
                    }
                    if (!ok) {
                        offenders += "$rel:${index + 1}  —— ${line.trim()}"
                    }
                }
                if (fileHasChecks) scanned += rel
            }

        // 防"扫描空转"：路径解析一旦坏掉，offenders 为空会让本测试假绿。
        assertTrue(
            "扫描结果为空或过少（只扫到 ${scanned.size} 个文件），八成是源码根目录没定位对：$root",
            scanned.size >= 5,
        )

        if (offenders.isNotEmpty()) {
            fail(
                "包住挂起调用的通用 catch 没有先接 CancellationException（取消会被吞成业务失败）：\n" +
                    offenders.joinToString("\n") +
                    "\n\n改法：在业务 catch 之前加 " +
                    "`catch (cancellation: CancellationException) { throw cancellation }`；" +
                    "onFailure 写法在 lambda 首行加 `if (throwable is CancellationException) throw throwable`。" +
                    "确实不含挂起调用的（取消不可能穿过）才进 exemptions 并写清理由。",
            )
        }
    }

    private fun File.invariantSeparatorsPath(): String = path.replace('\\', '/')

    /** 与 `InputLimitsCallSiteContractTest` 同一套定位方式：兼容工作目录是 `app/` 还是仓库根。 */
    private fun sourceRoot(): File {
        val candidates = listOf(
            "src/main/java/com/ironhabit/app",
            "app/src/main/java/com/ironhabit/app",
        )
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val tried = mutableListOf<String>()
        while (dir != null) {
            candidates.forEach { rel ->
                val candidate = File(dir, rel)
                tried += candidate.absolutePath
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("未找到主源码根目录（user.dir=${System.getProperty("user.dir")}）；已尝试：\n${tried.joinToString("\n")}")
    }
}
