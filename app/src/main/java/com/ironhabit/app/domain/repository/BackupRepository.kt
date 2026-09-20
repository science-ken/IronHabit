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
