package com.yzinsta.downloader.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yzinsta.downloader.R
import com.yzinsta.downloader.data.session.SessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    session: SessionState.LoggedIn,
    onRetryVerification: () -> Unit,
    onLogout: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    session.username?.let { stringResource(R.string.logged_in_as, it) }
                        ?: stringResource(R.string.logged_in_unverified)
                )
            },
            actions = { TextButton(onClick = onLogout) { Text(stringResource(R.string.logout)) } },
        )
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            session.warning?.let {
                ErrorCard(it, actionLabel = stringResource(R.string.retry), onAction = onRetryVerification)
            }
            content()
        }
    }
}
