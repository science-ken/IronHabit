package com.ironhabit.app.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「表单数字必须走 limits 对象」的**契约测试**（扫描式，纯 JVM / 离线可跑）。
 *
 * ## 为什么需要它
 * 2026-09-20 那轮审计查出三条根因，其中一条是**「约定靠自觉，一个一个调用点漏」**：
 * `InputLimits` 与 `withTransaction` 都是立了规矩但没人 enforce 的约定。当时逐个补了调用点，
 * 但**没有**同时把规矩变成机械检查 —— 也就是说下一个新增的表单照样能漏，而这次漏在哪个文件上
 * 全靠 code review 的记性。本测试就是补那一格，形状照 [StringResourcePlaceholderContractTest]。
 *
 * ## 规则
 * `ui/screens/` 下任何 Kotlin 文件，只要把文本解析成数字（`toIntOrNull` / `toLongOrNull` /
 * `toFloatOrNull` / `toDoubleOrNull`），就必须同时引用一个**取值范围的唯一真源**：
 * `InputLimits.` / `ProfileLimits.` / `rangeFor(`。否则红灯，除非它在 [exemptions] 里
 * 带着理由登记 —— 豁免表本身也会腐烂，所以额外断言"每条豁免今天仍然成立"。
 *
 * 扫描范围刻意只到 `ui/screens/`（用户手输的那一层），`domain/` 与 `ui/theme/` 里的
 * `toLongOrNull(16)` 这类解析的是自家数据（十六进制颜色、导出用的库内值），不是用户输入，
 * 纳进来只会稀释这条约定。
 *
 * ## 已知局限（别把它当严丝合缝）
 * 判据是**文件级**的：一个文件引用了 `InputLimits` 就整文件放行，所以"四格里只校验了三格"
 * 这种漏法它抓不到 —— 那一层靠 [com.ironhabit.app.ui.screens.checkin.CheckInFormValidityTest]
 * 这类逐字段的单测。本测试守的是**"新增一个完全没上判据的表单"**这一格，那也正是历史上真漏过的那格。
 */
class InputLimitsCallSiteContractTest {

    /** 解析文本为数字的调用点。 */
    private val parseSite = Regex("""\.(to(?:Int|Long|Float|Double)OrNull)\s*\(""")

    /** 取值范围的唯一真源引用。 */
    private val limitsSource = Regex("""\b(InputLimits\.|ProfileLimits\.|rangeFor\()""")

    /**
     * 带理由的豁免。key 是相对 `src/main/java/com/ironhabit/app/` 的路径。
     *
     * **每条都必须写清是"合法不需要"还是"该修但还没修"** —— 后者是欠账，不是豁免。
     */
    private val exemptions: Map<String, String> = mapOf(
        "ui/screens/habit/AddEditHabitViewModel.kt" to
            "欠账：习惯目标值只判了「能不能 parse」（:201），没有任何上下界，负数与 1e18 都能存进库并原样画到" +
            "习惯行上。补不了是因为它**没有单位**（同一个字段要装「4 杯」「30 分钟」「8 小时」），" +
            "跨单位的合理上界是产品判断，得用户定，不该由测试或代码悄悄编一个数。",
    )

    @Test
    fun everyFormThatParsesNumbersGoesThroughALimitsSource() {
        val root = sourceRoot()
        val offenders = mutableListOf<String>()
        val filesWithSites = mutableListOf<Pair<String, Int>>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "ui${File.separator}screens${File.separator}" in it.path || "/ui/screens/" in it.path }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                val src = file.readText()
                val sites = parseSite.findAll(src).count()
                if (sites == 0) return@forEach
                filesWithSites += rel to sites
                if (limitsSource.containsMatchIn(src) || rel in exemptions) return@forEach
                offenders += "$rel  —— $sites 处把文本解析成数字，但全文件没有一处引用取值范围真源"
            }

        // 防"扫描空转"：路径解析一旦坏掉，offenders 为空会让本测试假绿。
        assertTrue("扫描结果为空或过少（只找到 ${filesWithSites.size} 个含解析点的文件），" +
            "八成是源码根目录没定位对：$root", filesWithSites.size >= 5)

        if (offenders.isNotEmpty()) {
            fail(
                "新增/改动的表单绕开了取值范围唯一真源。当前含解析点的文件：\n" +
                    filesWithSites.joinToString("\n") { "  ${it.second}  ${it.first}" } +
                    "\n\n不合规：\n" + offenders.joinToString("\n") +
                    "\n\n改法：解析后过一道 InputLimits.isValid*（或 coerce*）；" +
                    "确实不需要范围（解析的不是用户输入）就进 exemptions 并写清理由。",
            )
        }
    }

    /** 豁免表不许腐烂：每条今天仍然得成立（文件在、且确实还有解析点）。 */
    @Test
    fun exemptionsAreStillEarned() {
        val root = sourceRoot()
        val stale = exemptions.keys.filter { rel ->
            val file = File(root, rel)
            !file.isFile || parseSite.findAll(file.readText()).count() == 0
        }
        assertTrue(
            "这些豁免项已经不再需要豁免（文件删了、或解析点没了、或已补上判据），请从 exemptions 里删掉：\n" +
                stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    /** 与 [StringResourcePlaceholderContractTest] 同一套定位方式：兼容工作目录是 `app/` 还是仓库根。 */
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
