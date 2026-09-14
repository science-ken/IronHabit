package com.ironhabit.app.domain.usecase

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.repository.BackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * 导出数据用例（架构 §3.4）。
 *
 * 把 [BackupRepository.export] 产出的 JSON 写入应用私有目录 `files/exports/`，
 * 再经 `FileProvider` 生成可分享的 [Uri]。**纯本地文件操作，无任何网络**。
 *
 * `file_paths.xml`（T01 已提供）声明了 `files-path name="exports" path="exports/"`。
 */
class ExportDataUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend operator fun invoke(): Uri = withContext(ioDispatcher) {
        val json = backupRepository.export()

        val exportsDir = File(context.filesDir, EXPORTS_DIR_NAME).apply { mkdirs() }
        val fileName = "ironhabit_backup_${clock.now().toEpochMilliseconds()}.json"
        val file = File(exportsDir, fileName)
        file.writeText(json, Charsets.UTF_8)

        FileProvider.getUriForFile(
            context,
            "${context.packageName}$FILE_PROVIDER_SUFFIX",
            file,
        )
    }

    private companion object {
        const val EXPORTS_DIR_NAME: String = "exports"

        /** 与 AndroidManifest 中 FileProvider 的 authorities（`${applicationId}.fileprovider`）一致。 */
        const val FILE_PROVIDER_SUFFIX: String = ".fileprovider"
    }
}
