package com.ironhabit.app.domain.usecase

import android.content.Context
import android.net.Uri
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.repository.BackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * 导入数据用例（架构 §3.4）。
 *
 * 从用户选择的 [Uri] 读取 JSON，交给 [BackupRepository.import] 在**事务**内整体替换写库。
 * 失败原因由仓库层以中文 `message` 返回，本用例原样透传（UI 不暴露英文/堆栈）。
 */
class ImportDataUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend operator fun invoke(uri: Uri): Result<Unit> = withContext(ioDispatcher) {
        try {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: return@withContext Result.failure(IllegalArgumentException(ERROR_OPEN_FILE))

            backupRepository.import(json)
        } catch (cancellation: CancellationException) {
            // 协程取消必须透传，切勿当作业务失败吞掉
            throw cancellation
        } catch (throwable: Exception) {
            Result.failure(throwable)
        }
    }

    private companion object {
        const val ERROR_OPEN_FILE: String = "无法读取所选文件，请重新选择备份文件"
    }
}
