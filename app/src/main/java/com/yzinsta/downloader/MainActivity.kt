package com.yzinsta.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import com.yzinsta.downloader.data.python.InstaloaderBridge
import com.yzinsta.downloader.ui.theme.YzInstaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YzInstaTheme {
                Scaffold { padding ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        PythonStatus((application as YzInstaApp).container.bridge)
                    }
                }
            }
        }
    }
}

@Composable
private fun PythonStatus(bridge: InstaloaderBridge) {
    var version by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        version = bridge.instaloaderVersion()
    }
    Text(
        version?.let { stringResource(R.string.python_ready, it) }
            ?: stringResource(R.string.python_loading)
    )
}
