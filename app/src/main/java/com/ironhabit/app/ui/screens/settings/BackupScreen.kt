package com.ironhabit.app.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.ui.components.LocalSnackbarHostState

/** 导入文件类型过滤：仅 JSON。 */
private const val MIME_JSON = "application/json"

/**
 * 「数据备份」页：导出 JSON（分享）/ 导入 JSON（覆盖前二次确认）。
 *
 * 导出成功后用 [Intent.ACTION_SEND] + [Intent.createChooser] 分享（带读权限 Grant）。
 * 导入选择文件用 [ActivityResultContracts.OpenDocument]，选定后先弹确认框。
 */
@Composable
fun BackupScreen(
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.onImportPicked(uri)
        }
    }

    val snackbarText: String? = uiState.snackbarRes?.let { res -> stringResource(res) }
    LaunchedEffect(snackbarText) {
        if (snackbarText != null) {
            snackbarHostState.showSnackbar(snackbarText)
            viewModel.onConsumeSnackbar()
        }
    }

    LaunchedEffect(uiState.exportUri) {
        val uri = uiState.exportUri
        if (uri != null) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = MIME_JSON
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, null))
            viewModel.onConsumeExportUri()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.title_backup),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // ---- 导出 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.backup_export_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.backup_export_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = viewModel::onExport,
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.action_export))
                }
            }
        }

        // ---- 导入 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.backup_import_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.backup_import_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { importLauncher.launch(arrayOf(MIME_JSON)) },
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.action_import))
                }
            }
        }
    }

    if (uiState.showImportConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::onCancelImport,
            title = { Text(text = stringResource(R.string.dialog_import_confirm_title)) },
            text = { Text(text = stringResource(R.string.dialog_import_confirm_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::onConfirmImport) {
                    Text(text = stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onCancelImport) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
