package com.yzinsta.downloader.data.profile

import com.yzinsta.downloader.data.download.MediaFile

data class ProfileInfo(
    val username: String,
    val fullName: String,
    val posts: Int,
    val isPrivate: Boolean,
    val followed: Boolean,
)

/** What to download from a profile. [limit] applies to posts and reels; 0 = all. */
data class ProfileOptions(
    val stories: Boolean = true,
    val highlights: Boolean = true,
    val posts: Boolean = true,
    val reels: Boolean = false,
    val limit: Int = 20,
) {
    val isEmpty get() = !(stories || highlights || posts || reels)
}

data class ProfileCounters(val downloaded: Int = 0, val skipped: Int = 0, val failed: Int = 0)

/** One profile_next() result. */
sealed interface ProfileStep {
    val counters: ProfileCounters

    /** Where the job is: "stories" | "highlights" | "posts" | "reels", nth item, expected count if known. */
    data class Position(val section: String, val position: Int, val expected: Int?)

    data class Downloaded(
        override val counters: ProfileCounters,
        val at: Position,
        val itemId: String,
        val kind: String,
        val files: List<MediaFile>,
    ) : ProfileStep

    data class Skipped(override val counters: ProfileCounters, val at: Position, val itemId: String) : ProfileStep
    data class Failed(override val counters: ProfileCounters, val at: Position, val itemId: String, val detail: String?) : ProfileStep
    data class Finished(override val counters: ProfileCounters) : ProfileStep
}
