package com.ironhabit.app.ui.screens.settings

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ironhabit.app.R
import com.ironhabit.app.domain.repository.BackupImportReport
import com.ironhabit.app.domain.usecase.ExportDataUseCase
import com.ironhabit.app.domain.usecase.ImportDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「数据备份」UI 状态（不可变）。
 *
 * @property isBusy 导入/导出进行中（禁用按钮）
 * @property exportUri 导出成功后的分享 Uri（UI 消费后置空）
 * @property showImportConfirm 是否展示导入确认框（覆盖全部数据的二次确认）
 * @property pendingImportUri 待导入的文件 Uri（确认后使用）
 * @property snackbarRes 一次性 Snackbar 资源 id
 */
data class BackupUiState(
    val isBusy: Boolean = false,
    val exportUri: Uri? = null,
    val showImportConfirm: Boolean = false,
    val pendingImportUri: Uri? = null,
    @StringRes val snackbarRes: Int? = null,
)

/**
 * 导入结果 → 提示文案。分档规则单独抽成顶层纯函数是为了能在 JVM 里逐档测
 * （`BackupViewModel` 要 `Uri` 与协程，起测试的成本高得多）。
 *
 * 优先级这样排是刻意的：**"数据进去了但设置没写回" 盖过 "老备份没带饮食表"** ——
 * 前者要用户去做一件事（去设置里确认提醒时间），后者只是少恢复了一张表；
 * 两条同时命中时先说更要紧的那条，不能指望用户读完一条提示还期待下一条。
 */
@StringRes
internal fun importSnackbarRes(report: BackupImportReport?): Int = when {
    report == null -> R.string.msg_import_failed
    !report.settingsApplied || !report.alarmsRescheduled -> R.string.msg_import_partial_settings
    report.dietSkipped -> R.string.msg_import_success_diet_skipped
    else -> R.string.msg_import_success
}

/**
 * 「数据备份」ViewModel：导出 JSON 分享 / 选择文件导入（导入前二次确认）。
 *
 * 导入导出均由 T03 的 [ExportDataUseCase] / [ImportDataUseCase] 完成（JSON 文件 + FileProvider）。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val exportData: ExportDataUseCase,
    private val importData: ImportDataUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 导出全部数据为 JSON 文件（成功后产出分享 Uri）。 */
    fun onExport() {
        if (_uiState.value.isBusy) {
            return
        }
        _uiState.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                val uri = exportData()
                _uiState.update {
                    it.copy(isBusy = false, exportUri = uri, snackbarRes = R.string.msg_export_success)
                }
            } catch (throwable: Throwable) {
                _uiState.update { it.copy(isBusy = false, snackbarRes = R.string.msg_export_failed) }
            }
        }
    }

    /** 消费一次导出 Uri（分享后置空，避免重复弹分享）。 */
    fun onConsumeExportUri() = _uiState.update { it.copy(exportUri = null) }

    /**
     * 分享面板拉不起来（设备上没有能收 `application/json` 的应用）。
     *
     * 只说"失败"是不够的：文件确实已经写进 `files/exports/` 了，
     * 报"导出失败"会让人以为白点了一次。
     */
    fun onExportShareFailed() =
        _uiState.update { it.copy(snackbarRes = R.string.msg_export_no_share_target) }

    /** 已选定待导入文件 → 弹出确认框。 */
    fun onImportPicked(uri: Uri) =
        _uiState.update { it.copy(pendingImportUri = uri, showImportConfirm = true) }

    /** 取消导入。 */
    fun onCancelImport() =
        _uiState.update { it.copy(showImportConfirm = false, pendingImportUri = null) }

    /** 确认导入（覆盖全部数据）。 */
    fun onConfirmImport() {
        val uri = _uiState.value.pendingImportUri ?: return
        _uiState.update { it.copy(showImportConfirm = false, isBusy = true) }
        viewModelScope.launch {
            try {
                val result = importData(uri)
                // 备份早于 v5 时饮食四张表**没被替换**（本机记录保住了）；设置/闹钟在事务外，
                // 也可能没写回。这些都要说出来 —— 分档规则见 `importSnackbarRes`。
                val snackbarRes: Int = importSnackbarRes(result.getOrNull())
                _uiState.update {
                    it.copy(isBusy = false, pendingImportUri = null, snackbarRes = snackbarRes)
                }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        pendingImportUri = null,
                        snackbarRes = R.string.msg_import_failed,
                    )
                }
            }
        }
    }

    /** 消费一次 Snackbar。 */
    fun onConsumeSnackbar() = _uiState.update { it.copy(snackbarRes = null) }
}
