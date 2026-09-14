package com.ironhabit.app.domain.repository

/**
 * 备份仓库接口（纯本地 JSON 导出 / 导入，无任何网络依赖）。
 */
interface BackupRepository {

    /** 将全部数据导出为 JSON 字符串。 */
    suspend fun export(): String

    /**
     * 从 JSON 字符串整体替换导入（事务内执行，失败整体回滚）。
     *
     * @return 成功为 `Result.success(Unit)`，失败为 `Result.failure`（异常 message 为中文原因）
     */
    suspend fun import(json: String): Result<Unit>
}
