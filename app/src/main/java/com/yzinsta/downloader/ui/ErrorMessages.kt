package com.yzinsta.downloader.ui

import androidx.annotation.StringRes
import com.yzinsta.downloader.R
import com.yzinsta.downloader.domain.AppError

@StringRes
fun AppError.messageRes(): Int = when (this) {
    AppError.NotLoggedIn -> R.string.error_not_logged_in
    AppError.SessionExpired -> R.string.error_session_expired
    AppError.ChallengeRequired -> R.string.error_challenge
    AppError.PrivateOrNoAccess -> R.string.error_private
    AppError.NotFound -> R.string.error_not_found
    AppError.RateLimited -> R.string.error_rate_limited
    AppError.Network -> R.string.error_network
    AppError.InvalidLink -> R.string.error_invalid_link
    is AppError.InvalidCookies -> R.string.error_invalid_cookies
    is AppError.Storage -> R.string.error_storage
    is AppError.Unknown -> R.string.error_unknown
}
