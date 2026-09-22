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
        val fileName = "$EXPORT_PREFIX${clock.now().toEpochMilliseconds()}$EXPORT_SUFFIX"
        val file = File(exportsDir, fileName)
        file.writeText(json, Charsets.UTF_8)
        pruneOldExports(exportsDir)

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

/**
 * 只留最近 [KEEP_RECENT_EXPORTS] 份导出。
 *
 * 每点一次导出就落一份 ~100KB 的文件，以前从不清理 —— 真机上已经堆了 6 份，
 * 而且界面上没有任何地方能列出或删除它们（走查 #10）。
 *
 * 按文件名里那段**毫秒时间戳的数值**排序，不按文件名字典序、也不按 `lastModified`：
 * 字典序要求时间戳定宽才等于时间序（位数少一位的 `999…` 会排到 `1700…` 后面，
 * 被当成最新的一份留下、把真正最新的删掉）；`lastModified` 会被云盘/同步工具改写成
 * "刚刚动过"，同样会把最新的当成最旧的删。
 * 解析不出时间戳的文件一律不碰（不是本用例写出来的东西）。
 * 删除失败不改导出结果：留几份旧文件不该让用户"导出失败"。
 */
internal fun pruneOldExports(dir: File) {
    val exports: List<Pair<Long, File>> = dir.listFiles()
        ?.mapNotNull { file -> exportTimestamp(file.name)?.let { millis -> millis to file } }
        .orEmpty()
    exports
        .sortedByDescending { (millis, _) -> millis }
        .drop(KEEP_RECENT_EXPORTS)
        .forEach { (_, file) -> file.delete() }
}

/** 导出文件名 → 写入时的毫秒时间戳；不符合导出命名格式 → `null`。 */
private fun exportTimestamp(fileName: String): Long? =
    fileName
        .removePrefix(EXPORT_PREFIX)
        .removeSuffix(EXPORT_SUFFIX)
        .takeIf { fileName.startsWith(EXPORT_PREFIX) && fileName.endsWith(EXPORT_SUFFIX) }
        ?.toLongOrNull()

/** 导出文件名前缀 / 后缀（写与删共用一份，避免两处各拼一遍就拼歪了）。 */
internal const val EXPORT_PREFIX: String = "ironhabit_backup_"
internal const val EXPORT_SUFFIX: String = ".json"

/** 保留最近几份导出。 */
private const val KEEP_RECENT_EXPORTS: Int = 3
