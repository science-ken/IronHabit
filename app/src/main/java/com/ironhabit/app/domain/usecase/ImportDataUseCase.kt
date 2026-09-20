package com.ironhabit.app.domain.usecase

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.repository.BackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
            // 第一道闸：provider 报了大小就先判（不报的走第二道）。
            val reportedSize: Long = querySize(uri)
            if (reportedSize > MAX_BYTES) {
                throw IllegalArgumentException(ERROR_TOO_LARGE)
            }
            val json: String = context.contentResolver.openInputStream(uri)?.use { input ->
                readCapped(input)
            } ?: return@withContext Result.failure(IllegalArgumentException(ERROR_OPEN_FILE))

            backupRepository.import(json)
        } catch (cancellation: CancellationException) {
            // 协程取消必须透传，切勿当作业务失败吞掉
            throw cancellation
        } catch (throwable: Exception) {
            Result.failure(throwable)
        }
    }

    /**
     * 分块读，超过上限立即放弃。
     *
     * 原来是一句 `input.readBytes()`：整个文件先进 `ByteArray` 再转 UTF-16 `String`，
     * 峰值内存约等于文件大小的 3 倍 —— 用户在手选的 SAF 选择器里点到一个几百 MB 的文件
     * 就直接 OOM 崩掉（而且是在读文件阶段崩，事务还没开始，数据不会丢，但界面会闪退）。
     */
    private fun readCapped(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read: Int = input.read(chunk)
            if (read < 0) break
            output.write(chunk, 0, read)
            total += read
            if (total > MAX_BYTES) throw IllegalArgumentException(ERROR_TOO_LARGE)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /** provider 不报大小时返回 `-1`（交给 [readCapped] 兜底），不能当成"空文件"。 */
    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else UNKNOWN_SIZE
        } ?: UNKNOWN_SIZE
    }.getOrDefault(UNKNOWN_SIZE)

    private companion object {
        const val ERROR_OPEN_FILE: String = "无法读取所选文件，请重新选择备份文件"
        const val ERROR_TOO_LARGE: String = "备份文件过大（超过 64 MB），请确认选的是本 App 导出的 JSON"

        /** 上限 64 MB：全库 JSON 实测在 MB 量级，留足几十倍余量。 */
        const val MAX_BYTES: Long = 64L * 1024L * 1024L

        const val READ_CHUNK_BYTES: Int = 8 * 1024
        const val UNKNOWN_SIZE: Long = -1L
    }
}
