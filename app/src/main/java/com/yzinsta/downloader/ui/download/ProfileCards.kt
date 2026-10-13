package com.yzinsta.downloader.ui.download

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yzinsta.downloader.R
import com.yzinsta.downloader.data.profile.ProfileCounters
import com.yzinsta.downloader.data.profile.ProfileDownloadService
import com.yzinsta.downloader.data.profile.ProfileInfo
import com.yzinsta.downloader.data.profile.ProfileJobState
import com.yzinsta.downloader.data.profile.ProfileOptions
import com.yzinsta.downloader.ui.home.ErrorCard
import kotlinx.coroutines.delay

private val LIMITS = listOf(20, 50, 0)

@Composable
fun ProfileOptionsCard(
    info: ProfileInfo,
    busy: Boolean,
    onStart: (ProfileOptions) -> Unit,
    onCancel: () -> Unit,
) {
    var stories by rememberSaveable { mutableStateOf(true) }
    var highlights by rememberSaveable { mutableStateOf(true) }
    var posts by rememberSaveable { mutableStateOf(true) }
    var reels by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableStateOf(20) }
    val options = ProfileOptions(stories, highlights, posts, reels, limit)

    // Android 13+: ask once for notifications so the progress is visible; start either way.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onStart(options)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("@${info.username}", style = MaterialTheme.typography.titleMedium)
            if (info.fullName.isNotBlank()) Text(info.fullName, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.profile_post_count, info.posts) +
                    if (info.isPrivate) " · " + stringResource(R.string.profile_private) else "",
                style = MaterialTheme.typography.bodySmall,
            )
            if (info.isPrivate && !info.followed) {
                Text(stringResource(R.string.error_private), color = MaterialTheme.colorScheme.error)
            }
            CheckRow(stringResource(R.string.section_stories), stories) { stories = it }
            CheckRow(stringResource(R.string.section_highlights), highlights) { highlights = it }
            CheckRow(stringResource(R.string.section_posts), posts) { posts = it }
            CheckRow(stringResource(R.string.section_reels), reels) { reels = it }

            Text(stringResource(R.string.profile_limit_label), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LIMITS.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = limit == value,
                        onClick = { limit = value },
                        shape = SegmentedButtonDefaults.itemShape(index, LIMITS.size),
                    ) {
                        Text(if (value == 0) stringResource(R.string.profile_limit_all) else stringResource(R.string.profile_limit_n, value))
                    }
                }
            }
            if (limit == 0) {
                Text(stringResource(R.string.profile_all_warning), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.profile_skip_note), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
            if (busy) Text(stringResource(R.string.profile_busy), color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                Button(
                    enabled = !busy && !options.isEmpty && !(info.isPrivate && !info.followed),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onStart(options)
                        }
                    },
                ) { Text(stringResource(R.string.profile_start)) }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
fun ProfileJobCard(state: ProfileJobState, onStop: () -> Unit, onDismiss: () -> Unit) {
    if (state is ProfileJobState.Idle) return
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is ProfileJobState.Running -> {
                    Text(
                        stringResource(R.string.profile_notification_title, state.username) +
                            (state.at?.let { " · " + ProfileDownloadService.sectionLabel(context, it) } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val expected = state.at?.expected
                    if (expected != null && expected > 0) {
                        LinearProgressIndicator(
                            progress = { state.at.position.toFloat() / expected },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (state.waitingUntil != null) WaitingText(state.waitingUntil)
                    Counts(state.counters)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
                    }
                }
                is ProfileJobState.Finished -> Done(stringResource(R.string.profile_done_title, state.username), state.counters, onDismiss)
                is ProfileJobState.Cancelled -> Done(stringResource(R.string.profile_cancelled_title, state.username), state.counters, onDismiss)
                is ProfileJobState.Failed -> {
                    Text(stringResource(R.string.profile_failed_title, state.username), style = MaterialTheme.typography.titleSmall)
                    ErrorCard(state.error)
                    Counts(state.counters)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
                    }
                }
                ProfileJobState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun WaitingText(until: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (now < until) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val minutes = ((until - now).coerceAtLeast(0) / 60_000 + 1).toInt()
    Text(stringResource(R.string.profile_waiting, minutes), color = MaterialTheme.colorScheme.tertiary)
}

@Composable
private fun Counts(c: ProfileCounters) {
    Text(stringResource(R.string.profile_progress_counts, c.downloaded, c.skipped, c.failed),
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Done(title: String, counters: ProfileCounters, onDismiss: () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Counts(counters)
    Text(stringResource(R.string.profile_where), style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
    }
}
