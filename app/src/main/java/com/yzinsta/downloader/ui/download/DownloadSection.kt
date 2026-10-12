package com.yzinsta.downloader.ui.download

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.core.content.ContextCompat
import com.yzinsta.downloader.R
import com.yzinsta.downloader.data.download.DownloadStage
import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.ui.home.ErrorCard

@Suppress("DEPRECATION") // LocalClipboardManager: the suspend LocalClipboard API is overkill for plain text
@Composable
fun DownloadSection(state: DownloadUiState, vm: DownloadViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // API 26-28 write to the public Pictures/Movies dirs directly and need this permission.
    val needsLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.download()
    }
    val start = {
        if (needsLegacyPermission && ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.download()
        }
    }

    LaunchedEffect(state.autoStart) {
        if (state.autoStart) {
            vm.consumeAutoStart()
            start()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.link,
            onValueChange = vm::onLinkChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.link_label)) },
            placeholder = { Text("https://www.instagram.com/reel/…") },
            singleLine = true,
            enabled = !state.isRunning,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (state.link.isNotBlank()) start() }),
            trailingIcon = {
                TextButton(
                    onClick = { clipboard.getText()?.text?.let(vm::onLinkChange) },
                    enabled = !state.isRunning,
                ) { Text(stringResource(R.string.paste)) }
            },
        )
        Button(
            onClick = { start() },
            enabled = state.link.isNotBlank() && !state.isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.download)) }

        when (val status = state.status) {
            DownloadStatus.Idle -> Text(
                stringResource(R.string.download_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            is DownloadStatus.Running -> Progress(status.stage)
            is DownloadStatus.Done -> Card(Modifier.fillMaxWidth()) {
                val s = status.summary
                Text(
                    pluralStringResource(R.plurals.download_done, s.images + s.videos, s.images + s.videos) +
                        "\n" + stringResource(R.string.download_done_where),
                    modifier = Modifier.padding(16.dp),
                )
            }
            is DownloadStatus.Failed -> if (!status.error.requiresLogin) {
                ErrorCard(
                    status.error,
                    actionLabel = stringResource(R.string.retry).takeIf { status.error.isRetryable },
                    onAction = { start() },
                )
            }
        }
    }
}

private val AppError.isRetryable: Boolean
    get() = this is AppError.Network || this is AppError.RateLimited || this is AppError.Unknown ||
        this is AppError.Storage

@Composable
private fun Progress(stage: DownloadStage) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (stage) {
            DownloadStage.Fetching -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.stage_fetching))
            }
            is DownloadStage.Saving -> {
                LinearProgressIndicator(
                    progress = { if (stage.total == 0) 0f else stage.done.toFloat() / stage.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.stage_saving, stage.done, stage.total))
            }
        }
    }
}
