package com.ironhabit.app.domain.repository

/**
 * 一次导入的**结果说明**。
 *
 * 存在的唯一理由：有些表"这份备份里根本没有，所以我没动本机数据"是用户必须知道的 ——
 * 静默少恢复一张表，和静默多删一张表，界面上看到的都是同一句"导入成功"。
 *
 * @property dietSkipped 备份早于 v5（不含食物库与明细）→ 饮食四张表整体未替换，
 *   本机记录原样保留。见 `BackupRestoreRules.replacesDietTables`。
 */
data class BackupImportReport(
    val dietSkipped: Boolean,
    /**
     * 设置快照有没有写回 DataStore。
     *
     * ⚠️ 它在 Room 事务**之外**，失败不回滚已经导入的数据 —— 所以"设置没写回"不等于"导入失败"，
     * 必须分开告诉用户（见 `importSnackbarRes`），否则他会以为什么都没动而再导一次。
     */
    val settingsApplied: Boolean,
    /** 闹钟有没有重排（提醒时间/开关可能随备份变了）。与上一条同为事务外副作用。 */
    val alarmsRescheduled: Boolean,
)

/**
 * 备份仓库接口（纯本地 JSON 导出 / 导入，无任何网络依赖）。
 */
interface BackupRepository {

    /** 将全部数据导出为 JSON 字符串。 */
    suspend fun export(): String

    /**
     * 从 JSON 字符串整体替换导入（事务内执行，失败整体回滚）。
     *
     * @return 成功为 [BackupImportReport]（哪些表因备份版本过老而没被替换，写在里面），
     *   失败为 `Result.failure`（异常 message 为中文原因）
     */
    suspend fun import(json: String): Result<BackupImportReport>
}
