package com.ironhabit.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 「页面级错误态必须给得出路」的**契约测试**（扫描式，纯 JVM / 离线可跑）。
 *
 * ## 为什么需要它
 * 2026-09-25 那份外部审查一次列了 4 个同族缺陷（P1-3 / P2-3 / P2-7 / 补查的 V2-P2-2）：
 * 都是"读数据失败 → 整页换成错误态"，其中 4 页连重试按钮都没有，用户只能杀掉 App。
 * 而 `EmptyState` 自己的 KDoc 早就写着**「加载失败给 actionText = action_retry」** ——
 * 规矩立在文件头，13 个页面里漏了 4 个。这跟 2026-09-20 审计那条根因 3 是同一件事：
 * **约定靠自觉，一个一个调用点漏**。所以这一次不只补页面，把判据变成机械检查，
 * 形状照 [InputLimitsCallSiteContractTest]。
 *
 * ## 规则
 * 1. `ui/screens/` 下任何 `*Screen.kt`，只要画了错误分支（`errorRes != null`），
 *    同文件必须引用 `R.string.action_retry`；且同目录配对的 `*ViewModel.kt`
 *    必须有 `fun onRetry`（按钮得按得动，光有文案等于骗人）。
 * 2. 合并本地态与数据帧的 `merge` 不许写 `errorRes = data.errorRes ?: local.errorRes` ——
 *    旧错误会被每一次成功的数据帧续回来，整页永远停在错误分支（就是 P1-3 的机制）。
 *
 * ## 已知局限（别把它当严丝合缝）
 * 判据是**文件级**的：一个 Screen 只要提到 `action_retry` 就整文件放行，所以"两个错误分支
 * 里只有一个带按钮"这种漏法它抓不到；那一层靠真机走查。本测试守的是**"新增一页忘了给重试出口"**，
 * 也就是历史上真漏过的那一格。
 */
class ErrorStateRetryContractTest {

    /** 页面级错误分支：`when { errorRes != null -> ... }`。 */
    private val errorBranch = Regex("""errorRes\s*!=\s*null""")

    /** 把旧错误续回合并态的写法（P1-3 / P2-3 的机制本体）。 */
    private val stickyMerge = Regex("""errorRes\s*=\s*data\.errorRes\s*\?:""")

    @Test
    fun everyScreenWithAnErrorBranchHasARetryButtonAndARetryMethod() {
        val root = sourceRoot()
        val offenders = mutableListOf<String>()
        val checked = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name.endsWith("Screen.kt") }
            .forEach { screen ->
                val src: String = screen.readText()
                if (!errorBranch.containsMatchIn(src)) return@forEach
                val rel: String = screen.relativeTo(root).invariantSeparatorsPath
                checked += rel

                val hasRetryLabel = "R.string.action_retry" in src
                val viewModel = File(screen.parentFile, screen.name.removeSuffix("Screen.kt") + "ViewModel.kt")
                val hasRetryMethod = viewModel.isFile && "fun onRetry" in viewModel.readText()

                when {
                    !hasRetryLabel -> offenders += "$rel —— 画了错误态却没有「重试」按钮"
                    !hasRetryMethod -> offenders +=
                        "$rel —— 有按钮，但 ${viewModel.name} 里没有 fun onRetry（按下去没有实现）"
                }
            }

        // 防"扫描空转"：路径没定位对时 checked 会空，那正是假绿的形状。
        assertTrue(
            "只扫到 ${checked.size} 个带错误分支的页面，少于预期（八成是源码根目录没定位对：$root）",
            checked.size >= MIN_ERROR_PAGES,
        )

        if (offenders.isNotEmpty()) {
            fail(
                "以下页面进入错误态后用户没有出路。当前带错误分支的页面：\n" +
                    checked.joinToString("\n") { "  $it" } +
                    "\n\n不合规：\n" + offenders.joinToString("\n") +
                    "\n\n改法：错误分支用 EmptyState(text=…, actionText=stringResource(R.string.action_retry), " +
                    "onAction = viewModel::onRetry)，ViewModel 里照 TodayViewModel 加 retryTrigger + fun onRetry。",
            )
        }
    }

    @Test
    fun noMergeKeepsStaleErrorOnSuccessfulDataFrames() {
        val root = sourceRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { stickyMerge.containsMatchIn(it.readText()) }
            .map { file -> file.relativeTo(root).invariantSeparatorsPath }
            .toList()

        assertTrue(
            "这些文件把旧错误续在了成功的数据帧上（错误页会赖着不走，见类注释规则 2）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 与 [InputLimitsCallSiteContractTest] 同一套定位方式：兼容工作目录是 `app/` 还是仓库根。 */
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

    private companion object {
        /**
         * 2026-09-27 实测 13 页有错误分支。取下界 10 而不是 13：删掉一个页面不该
         * 把这条闸一起废掉，但整片扫不到时必须报警。
         */
        const val MIN_ERROR_PAGES = 10
    }
}
