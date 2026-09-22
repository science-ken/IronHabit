package com.ironhabit.app.ui.screens.settings

import com.ironhabit.app.R
import com.ironhabit.app.domain.repository.BackupImportReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [importSnackbarRes] —— 导入结果的分档提示（审查报告 P1-1）。
 *
 * 钉的是这一条：表已经整体替换并提交之后，事务外的两步（写 DataStore、重排闹钟）
 * 若抛异常，**不能**把整次导入报成"导入失败" —— 那会让用户以为数据没动而再导一次，
 * 于是二次全量清表。它们只能降级成"数据进来了，但设置要你自己去确认一遍"。
 */
class BackupImportMessageTest {

    private fun report(
        dietSkipped: Boolean = false,
        settingsApplied: Boolean = true,
        alarmsRescheduled: Boolean = true,
    ) = BackupImportReport(
        dietSkipped = dietSkipped,
        settingsApplied = settingsApplied,
        alarmsRescheduled = alarmsRescheduled,
    )

    @Test
    fun nullReportIsTheOnlyImportFailure() {
        assertEquals(R.string.msg_import_failed, importSnackbarRes(null))
    }

    @Test
    fun cleanV5ImportSaysPlainSuccess() {
        assertEquals(R.string.msg_import_success, importSnackbarRes(report()))
    }

    @Test
    fun oldBackupWithoutDietTablesSaysWhatWasNotRestored() {
        assertEquals(
            "v4 及更早的备份没带饮食四张表，不说的话用户会以为食物库也回来了",
            R.string.msg_import_success_diet_skipped,
            importSnackbarRes(report(dietSkipped = true)),
        )
    }

    /** 核心：设置写回失败必须**不**报"导入失败"。 */
    @Test
    fun settingsFailureNeverMasqueradesAsImportFailure() {
        val res: Int = importSnackbarRes(report(settingsApplied = false))

        assertNotEquals(
            "数据已经进去了，报「导入失败」会诱导用户再导一次 = 二次清表",
            R.string.msg_import_failed,
            res,
        )
        assertEquals(R.string.msg_import_partial_settings, res)
    }

    @Test
    fun alarmRescheduleFailureUsesTheSameWarning() {
        assertEquals(
            R.string.msg_import_partial_settings,
            importSnackbarRes(report(alarmsRescheduled = false)),
        )
    }

    @Test
    fun settingsWarningOutranksTheDietSkippedNote() {
        assertEquals(
            "两条同时命中时先说要紧的那条：设置没写回需要用户去做一件事，少恢复一张表不需要",
            R.string.msg_import_partial_settings,
            importSnackbarRes(report(dietSkipped = true, settingsApplied = false)),
        )
    }
}
