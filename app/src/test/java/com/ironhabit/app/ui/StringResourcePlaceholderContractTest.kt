package com.ironhabit.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 文案占位符 ↔ 实参类型的**契约测试**（纯 JVM / 离线可跑，不依赖 Robolectric）。
 *
 * ## 为什么需要它
 * `Context.getString(res, *args)` / Compose `stringResource(res, *args)` 最终走 `String.format`：
 * 若资源用了数值占位符 `%1$d`，而实参是 `String`（或反之），**运行时**会抛
 * `java.util.IllegalFormatConversionException`。这在编译期**完全不可见** ——
 * 本项目历史上已因同类问题崩过一次（`92263ee` 的 `msg_diet_filtered`：
 * `%1$d` 收到 `filteredCount.toString()` → 用户一勾忌口、生成计划即崩）。
 *
 * ## 覆盖范围（两个「String / Int 实参」Snackbar 通道）
 * - 通道 A：`TodayUiState.snackbarArgs` / `SettingsViewModel.snackbarArgs`（`List<String>`）
 *   → 配套资源必须是 `%s`，**不得**出现 `%d`。
 * - 通道 B：`AiCoachViewModel.snackbarArgs`（`List<Any>`，实参为 Int）与
 *   `PlanBasisItem.args`（`List<Any>`，实参为 Int）→ 资源须为 `%d`，**不得**出现 `%s`。
 *
 * 说明：本类不解析全部资源（那需要 Android 资源合并），只锚定**已确认走上述通道**的少数资源，
 * 这一组正是历史上真正踩坑的窄面。
 */
class StringResourcePlaceholderContractTest {

    /** 数值占位符：`%d` 或定位式 `%1$d`。 */
    private val numericPlaceholder = Regex("""%(\d+\${'$'})?d""")

    /** 字符串占位符：`%s` 或定位式 `%1$s`。 */
    private val stringPlaceholder = Regex("""%(\d+\${'$'})?s""")

    /** 通道 A：实参为 `String`（`List<String>` Snackbar）→ 只能 `%s`。 */
    private val stringArgChannel = listOf(
        "msg_streak_up", // Today：连续 N 天（实参 current.toString()）
        "msg_diet_filtered", // Today：忌口过滤计数（本次崩溃的主角）
        "msg_reminder_set", // Settings：提醒时间（实参 formatTime(...)）
    )

    /** 通道 B：实参为 `Int`（`List<Any>` Snackbar / PlanBasisItem.args）→ 用 `%d`。 */
    private val intArgChannel = listOf(
        "ai_plan_written_hint", // AiCoach：写入条数（实参 writtenCount: Int）
        "basis_overload", // AiCoach 依据：做满动作数（实参 overloadCount: Int）
    )

    @Test
    fun stringArgChannel_resourcesUseStringPlaceholderOnly() {
        val strings = loadStrings()
        stringArgChannel.forEach { name ->
            val text = strings[name] ?: error("strings.xml 缺少资源：$name")
            assertTrue(
                "$name 走 String 实参通道，必须含 %s 占位符，实际为「$text」",
                stringPlaceholder.containsMatchIn(text),
            )
            assertFalse(
                "$name 走 String 实参通道，不能含 %d 占位符（会抛 IllegalFormatConversionException），实际为「$text」",
                numericPlaceholder.containsMatchIn(text),
            )
        }
    }

    @Test
    fun intArgChannel_resourcesUseNumericPlaceholder() {
        val strings = loadStrings()
        intArgChannel.forEach { name ->
            val text = strings[name] ?: error("strings.xml 缺少资源：$name")
            assertTrue(
                "$name 走 Int 实参通道，必须含 %d 占位符，实际为「$text」",
                numericPlaceholder.containsMatchIn(text),
            )
            assertFalse(
                "$name 走 Int 实参通道，不应含 %s 占位符，实际为「$text」",
                stringPlaceholder.containsMatchIn(text),
            )
        }
    }

    /**
     * **直接复现历史崩溃**：用当年会崩的实参类型（String）去格式化 `msg_diet_filtered`。
     * 若占位符被改回 `%1$d`，本用例会抛 `IllegalFormatConversionException` 而失败。
     */
    @Test
    fun msgDietFiltered_formatsWithStringArg_doesNotThrow() {
        val text = loadStrings()["msg_diet_filtered"] ?: error("strings.xml 缺少资源：msg_diet_filtered")

        // 正是 TodayViewModel 传的实参：filteredCount.toString()
        val rendered = String.format(Locale.ROOT, text, "3")

        assertTrue("渲染结果应含过滤数，实际为「$rendered」", rendered.contains("3"))
    }

    // ---------------------------------------------------------------------
    // strings.xml 载入（不依赖 Robolectric：直接从模块目录读源文件）
    // ---------------------------------------------------------------------

    /** 解析 `strings.xml` 为 `name → 文本` 映射。 */
    private fun loadStrings(): Map<String, String> {
        val file = locateStringsXml()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = LinkedHashMap<String, String>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i)
            val name = el.attributes?.getNamedItem("name")?.nodeValue ?: continue
            map[name] = el.textContent ?: ""
        }
        return map
    }

    /**
     * 定位 `strings.xml`。Gradle 单测的工作目录默认是**模块目录**（`app/`），但为稳妥起见，
     * 从 `user.dir` 起逐级向上探测两种相对路径，命中即返回。
     */
    private fun locateStringsXml(): File {
        val relativeCandidates = listOf(
            "src/main/res/values/strings.xml", // 工作目录 = app/
            "app/src/main/res/values/strings.xml", // 工作目录 = 仓库根/
        )
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val tried = mutableListOf<String>()
        while (dir != null) {
            relativeCandidates.forEach { rel ->
                val candidate = File(dir, rel)
                tried += candidate.absolutePath
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        error("未找到 strings.xml（user.dir=${System.getProperty("user.dir")}）；已尝试：\n" + tried.joinToString("\n"))
    }
}
