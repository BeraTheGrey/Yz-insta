package com.yzinsta.downloader.data.profile

import com.yzinsta.downloader.data.download.MediaExporter
import com.yzinsta.downloader.data.history.DownloadHistory
import com.yzinsta.downloader.data.python.InstaloaderBridge
import com.yzinsta.downloader.data.session.SessionRepository
import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.domain.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ProfileJobState {
    data object Idle : ProfileJobState

    data class Running(
        val username: String,
        val at: ProfileStep.Position? = null,
        val counters: ProfileCounters = ProfileCounters(),
        /** Set while waiting out a rate limit / network error: wall-clock millis when it resumes. */
        val waitingUntil: Long? = null,
        val waitReason: AppError? = null,
    ) : ProfileJobState

    data class Finished(val username: String, val counters: ProfileCounters) : ProfileJobState
    data class Failed(val username: String, val error: AppError, val counters: ProfileCounters) : ProfileJobState
    data class Cancelled(val username: String, val counters: ProfileCounters) : ProfileJobState
}

/**
 * Runs a whole-profile download item by item: Python downloads one item into the cache,
 * it is exported to the gallery right away and written to the history, so a job that
 * is stopped (or killed) can be started again and skips what it already has.
 *
 * Instagram throttling is waited out with [backoffMinutes]; between items it pauses
 * [pauseBetweenItemsMs] on top of Instaloader's own pacing.
 */
class ProfileDownloadManager(
    private val bridge: InstaloaderBridge,
    private val exporter: MediaExporter,
    private val history: DownloadHistory,
    private val session: SessionRepository,
    private val workDir: File,
    private val backoffMinutes: List<Long> = listOf(3, 6, 10, 15, 15),
    private val pauseBetweenItemsMs: Long = 1_500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<ProfileJobState>(ProfileJobState.Idle)
    val state: StateFlow<ProfileJobState> = _state.asStateFlow()

    val isRunning get() = _state.value is ProfileJobState.Running

    suspend fun profileInfo(username: String): Outcome<ProfileInfo> = bridge.profileInfo(username)

    /** Clears a finished/failed/cancelled job from the UI. */
    fun dismiss() {
        if (!isRunning) _state.value = ProfileJobState.Idle
    }

    suspend fun run(username: String, options: ProfileOptions) {
        var counters = ProfileCounters()
        _state.value = ProfileJobState.Running(username)
        try {
            val known = history.knownIds(username)
            when (val started = bridge.profileStart(username, options, known)) {
                is Outcome.Failure -> return fail(username, started.error, counters)
                is Outcome.Success -> Unit
            }
            var retries = 0
            while (true) {
                when (val next = bridge.profileNext(workDir)) {
                    is Outcome.Failure -> {
                        val error = next.error
                        if (error.requiresLogin) {
                            session.invalidate(error)
                            return fail(username, error, counters)
                        }
                        if ((error is AppError.RateLimited || error is AppError.Network) && retries < backoffMinutes.size) {
                            val waitMs = backoffMinutes[retries++] * 60_000
                            _state.update { (it as? ProfileJobState.Running)?.copy(waitingUntil = clock() + waitMs, waitReason = error) ?: it }
                            delay(waitMs)
                            _state.update { (it as? ProfileJobState.Running)?.copy(waitingUntil = null, waitReason = null) ?: it }
                            continue
                        }
                        return fail(username, error, counters)
                    }
                    is Outcome.Success -> {
                        retries = 0
                        val step = next.value
                        counters = step.counters
                        when (step) {
                            is ProfileStep.Finished -> {
                                _state.value = ProfileJobState.Finished(username, counters)
                                return
                            }
                            is ProfileStep.Downloaded -> {
                                try {
                                    step.files.forEach { exporter.export(it) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    return fail(username, AppError.Storage(e.message), counters)
                                } finally {
                                    step.files.forEach { it.file.delete() }
                                }
                                history.record(username, step.itemId, step.kind, step.files.size)
                                _state.value = ProfileJobState.Running(username, step.at, counters)
                                delay(pauseBetweenItemsMs)
                            }
                            is ProfileStep.Skipped -> _state.value = ProfileJobState.Running(username, step.at, counters)
                            is ProfileStep.Failed -> _state.value = ProfileJobState.Running(username, step.at, counters)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            _state.value = ProfileJobState.Cancelled(username, counters)
            throw e
        } finally {
            withContext(NonCancellable) {
                bridge.profileCancel()
                workDir.deleteRecursively()
            }
        }
    }

    private fun fail(username: String, error: AppError, counters: ProfileCounters) {
        _state.value = ProfileJobState.Failed(username, error, counters)
    }
}
