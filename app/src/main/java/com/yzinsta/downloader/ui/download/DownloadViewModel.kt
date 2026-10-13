package com.yzinsta.downloader.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yzinsta.downloader.data.download.DownloadRepository
import com.yzinsta.downloader.data.download.DownloadStage
import com.yzinsta.downloader.data.download.DownloadSummary
import com.yzinsta.downloader.data.download.InstaLink
import com.yzinsta.downloader.data.download.LinkParser
import com.yzinsta.downloader.data.profile.ProfileDownloadManager
import com.yzinsta.downloader.data.profile.ProfileInfo
import com.yzinsta.downloader.data.profile.ProfileJobState
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

    /** A profile link was entered: show what can be downloaded from it. */
    data class ProfileReady(val info: ProfileInfo) : DownloadStatus
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
    private val profiles: ProfileDownloadManager,
) : ViewModel() {

    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    val profileJob: StateFlow<ProfileJobState> = profiles.state

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
        val target = LinkParser.parse(link)
        if (target is InstaLink.Profile) {
            job = viewModelScope.launch { loadProfile(target.username) }
            return
        }
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

    private suspend fun loadProfile(username: String) {
        val result = profiles.profileInfo(username)
        _state.update {
            when (result) {
                is Outcome.Success -> it.copy(status = DownloadStatus.ProfileReady(result.value))
                is Outcome.Failure -> it.copy(status = DownloadStatus.Failed(result.error))
            }
        }
        if (result is Outcome.Failure && result.error.requiresLogin) session.invalidate(result.error)
    }

    /** The options card handed the job to the service. */
    fun onProfileStarted() = _state.update { it.copy(status = DownloadStatus.Idle, link = "") }

    fun dismissProfileOptions() = _state.update { it.copy(status = DownloadStatus.Idle) }

    fun dismissProfileJob() = profiles.dismiss()

    fun consumeAutoStart() = _state.update { it.copy(autoStart = false) }
}
