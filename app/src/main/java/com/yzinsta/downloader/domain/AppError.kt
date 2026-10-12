package com.yzinsta.downloader.domain

/** Everything that can go wrong, in terms the UI can explain to the user. */
sealed interface AppError {
    /** Technical text for logs / "details"; never shown as the main message. */
    val detail: String? get() = null

    data object NotLoggedIn : AppError
    data object SessionExpired : AppError
    data object ChallengeRequired : AppError
    data object PrivateOrNoAccess : AppError
    data object NotFound : AppError
    data object RateLimited : AppError
    data object Network : AppError
    data object InvalidLink : AppError
    data class InvalidCookies(override val detail: String?) : AppError
    data class Storage(override val detail: String?) : AppError
    data class Unknown(override val detail: String?) : AppError

    /**
     * The stored session cannot be used any more; the user has to log in again
     * (for a challenge: log in again and pass Instagram's check in the WebView).
     */
    val requiresLogin: Boolean
        get() = this is NotLoggedIn || this is SessionExpired || this is InvalidCookies || this is ChallengeRequired
}

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}
