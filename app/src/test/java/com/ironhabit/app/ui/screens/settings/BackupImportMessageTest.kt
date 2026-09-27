package com.ironhabit.app.ui.screens.settings

import com.ironhabit.app.R
import com.ironhabit.app.domain.repository.BackupImportReport
import com.ironhabit.app.domain.usecase.BackupImportFailure
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
    fun unclassifiedFailureFallsBackToTheGenericMessage() {
        assertEquals(R.string.msg_import_failed, importSnackbarRes(null))
        assertEquals(
            "仓库层的解析错误不属于「面向用户的那两类原因」，不许被说成「文件太大」",
            R.string.msg_import_failed,
            importSnackbarRes(null, IllegalStateException("JSON 缺字段")),
        )
    }

    private fun failure(kind: BackupImportFailure.Kind) = BackupImportFailure(kind, "中文原因（只用于日志）")

    /**
     * 三种失败必须给出三句话（审查报告 P2-12）。
     *
     * 旧实现只看 `getOrNull()`：用例早就分清了"文件太大""读不到文件"，UI 却统统回一句
     * 「文件格式不正确」。用户挑到一个被锁住的云盘文件，得到的是一句假话，
     * 于是他会回头去检查 JSON 的格式。
     */
    @Test
    fun eachKnownFailureGetsItsOwnSentence() {
        assertEquals(
            R.string.msg_import_too_large,
            importSnackbarRes(null, failure(BackupImportFailure.Kind.FILE_TOO_LARGE)),
        )
        assertEquals(
            R.string.msg_import_unreadable,
            importSnackbarRes(null, failure(BackupImportFailure.Kind.FILE_UNREADABLE)),
        )
        val resources = setOf(
            R.string.msg_import_too_large,
            R.string.msg_import_unreadable,
            R.string.msg_import_failed,
        )
        assertEquals("三档提示得是三句不同的话，撞了就等于没分档", 3, resources.size)
    }

    /** 成功那几档不受 failure 影响（表已提交时绝不能说"导入失败"）。 */
    @Test
    fun aSuccessfulReportIsNeverOverriddenByAFailure() {
        assertEquals(
            R.string.msg_import_partial_settings,
            importSnackbarRes(report(settingsApplied = false), failure(BackupImportFailure.Kind.FILE_TOO_LARGE)),
        )
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
