package com.yzinsta.downloader.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yzinsta.downloader.R
import com.yzinsta.downloader.domain.AppError

@Composable
fun WelcomeScreen(
    reason: AppError?,
    onLogin: () -> Unit,
    onCookieLogin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCookieDialog by remember { mutableStateOf(false) }
    if (showCookieDialog) {
        CookieLoginDialog(
            onConfirm = {
                showCookieDialog = false
                onCookieLogin(it)
            },
            onDismiss = { showCookieDialog = false },
        )
    }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (reason != null) ErrorCard(reason)
        Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.login_button))
        }
        TextButton(onClick = { showCookieDialog = true }) {
            Text(stringResource(R.string.cookie_login_button))
        }
    }
}
