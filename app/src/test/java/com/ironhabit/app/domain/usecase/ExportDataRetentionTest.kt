package com.ironhabit.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 导出文件的保留策略（走查 #10）：只留最近 [KEEP_RECENT_EXPORTS] 份。
 *
 * 纯文件系统行为，不需要 Android —— 所以 `pruneOldExports` 是文件级 internal 函数，
 * 而不是 `ExportDataUseCase` 的私有成员（那样就得连 Context / FileProvider 一起 mock）。
 */
class ExportDataRetentionTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private fun touch(name: String): String {
        folder.newFile(name).writeText("{}")
        return name
    }

    private fun survivors(): List<String> =
        folder.root.list()?.sorted().orEmpty()

    /** 5 份留 3 份，删的是最旧的两份。 */
    @Test
    fun keepsTheThreeNewest() {
        val names = listOf(1_700_000_000_000L, 1_700_000_000_100L, 1_700_000_000_200L, 1_700_000_000_300L, 1_700_000_000_400L)
            .map { millis -> touch("$EXPORT_PREFIX$millis$EXPORT_SUFFIX") }

        pruneOldExports(folder.root)

        assertEquals(
            "应留下时间戳最大的三份",
            listOf(names[2], names[3], names[4]),
            survivors(),
        )
    }

    /** 没到上限时一份都不动。 */
    @Test
    fun underTheCapDeletesNothing() {
        val names = listOf(
            touch("$EXPORT_PREFIX${1_700_000_000_000L}$EXPORT_SUFFIX"),
            touch("$EXPORT_PREFIX${1_700_000_000_001L}$EXPORT_SUFFIX"),
        )

        pruneOldExports(folder.root)

        assertEquals(names.sorted(), survivors())
    }

    /**
     * 目录里可能有别人的文件（将来若加"导入上次导出"就会用到），
     * 名字不符合导出格式的一律不碰。
     */
    @Test
    fun ignoresFilesThatAreNotExports() {
        val keepMe: String = touch("notes.json")
        val keepMeToo: String = touch("ironhabit_backup_1700000000000.txt")
        listOf(1_700_000_000_000L, 1_700_000_000_001L, 1_700_000_000_002L, 1_700_000_000_003L)
            .forEach { millis -> touch("$EXPORT_PREFIX$millis$EXPORT_SUFFIX") }

        pruneOldExports(folder.root)

        val survivors: List<String> = survivors()
        assertEquals("非导出文件不该被删", true, keepMe in survivors && keepMeToo in survivors)
        // 数"导出文件"要前后缀都算：那个 .txt 同样是 ironhabit_backup_ 开头的。
        assertEquals(
            "导出文件仍只留三份",
            3,
            survivors.count { name -> name.startsWith(EXPORT_PREFIX) && name.endsWith(EXPORT_SUFFIX) },
        )
    }

    /**
     * 排序按时间戳的**数值**，不按文件名字典序。
     *
     * 这里故意放一个位数更少的时间戳：字典序下 `999…` 会排到 `1700…` 后面、
     * 被当成最新的一份留下，而真正最新的那份被删掉。
     */
    @Test
    fun shorterTimestampSortsAsOldest() {
        val short: String = touch("$EXPORT_PREFIX${999_999_999_999L}$EXPORT_SUFFIX")
        val keepers: List<String> = listOf(1_700_000_000_000L, 1_700_000_000_001L, 1_700_000_000_002L)
            .map { millis -> touch("$EXPORT_PREFIX$millis$EXPORT_SUFFIX") }

        pruneOldExports(folder.root)

        val survivors: List<String> = survivors()
        assertEquals("位数少的应被淘汰", false, short in survivors)
        assertEquals(keepers.sorted(), survivors)
    }
}
