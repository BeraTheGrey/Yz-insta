package com.yzinsta.downloader.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yzinsta.downloader.data.download.DownloadRepository
import com.yzinsta.downloader.data.download.DownloadStage
import com.yzinsta.downloader.data.download.DownloadSummary
import com.yzinsta.downloader.data.session.SessionRepository
import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.domain.Outcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    data class Running(val stage: DownloadStage) : DownloadStatus
    data class Done(val summary: DownloadSummary) : DownloadStatus
    data class Failed(val error: AppError) : DownloadStatus
}

data class DownloadUiState(
    val link: String = "",
    val status: DownloadStatus = DownloadStatus.Idle,
    /** A link arrived via the share sheet and should be downloaded without another tap. */
    val autoStart: Boolean = false,
) {
    val isRunning get() = status is DownloadStatus.Running
}

class DownloadViewModel(
    private val downloads: DownloadRepository,
    private val session: SessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        downloads.cleanUpStale()
    }

    fun onLinkChange(link: String) = _state.update { it.copy(link = link) }

    fun onSharedText(text: String) = _state.update {
        if (it.isRunning) it else it.copy(link = text.trim(), status = DownloadStatus.Idle, autoStart = true)
    }

    fun download() {
        if (job?.isActive == true) return
        val link = _state.value.link
        _state.update { it.copy(autoStart = false, status = DownloadStatus.Running(DownloadStage.Fetching)) }
        job = viewModelScope.launch {
            val result = downloads.download(link) { stage ->
                _state.update { it.copy(status = DownloadStatus.Running(stage)) }
            }
            _state.update {
                when (result) {
                    is Outcome.Success -> it.copy(status = DownloadStatus.Done(result.value), link = "")
                    is Outcome.Failure -> it.copy(status = DownloadStatus.Failed(result.error))
                }
            }
            if (result is Outcome.Failure && result.error.requiresLogin) {
                session.invalidate(result.error)
            }
        }
    }

    fun consumeAutoStart() = _state.update { it.copy(autoStart = false) }
}
