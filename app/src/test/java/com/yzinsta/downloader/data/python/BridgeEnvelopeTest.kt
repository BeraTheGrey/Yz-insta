package com.yzinsta.downloader.data.python

import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.domain.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeEnvelopeTest {

    private fun username(raw: String?) =
        BridgeEnvelope.decode(raw) { it.getJSONObject("data").getString("username") }

    @Test
    fun success() {
        assertEquals(Outcome.Success("alice"), username("""{"ok":true,"data":{"username":"alice"}}"""))
    }

    @Test
    fun `every python error code maps to its AppError`() {
        val expected = mapOf(
            "NOT_LOGGED_IN" to AppError.NotLoggedIn,
            "SESSION_EXPIRED" to AppError.SessionExpired,
            "CHALLENGE_REQUIRED" to AppError.ChallengeRequired,
            "PRIVATE" to AppError.PrivateOrNoAccess,
            "NOT_FOUND" to AppError.NotFound,
            "RATE_LIMITED" to AppError.RateLimited,
            "NETWORK" to AppError.Network,
            "INVALID_COOKIES" to AppError.InvalidCookies("d"),
            "UNKNOWN" to AppError.Unknown("d"),
        )
        for ((code, error) in expected) {
            assertEquals(code, Outcome.Failure(error), username("""{"ok":false,"error":{"code":"$code","detail":"d"}}"""))
        }
    }

    @Test
    fun `unknown code keeps detail`() {
        assertEquals(
            Outcome.Failure(AppError.Unknown("boom")),
            username("""{"ok":false,"error":{"code":"WHATEVER","detail":"boom"}}"""),
        )
    }

    @Test
    fun `malformed responses become Unknown`() {
        for (raw in listOf(null, "", "nope", """{"ok":true,"data":{}}""")) {
            val outcome = username(raw)
            assertTrue("$raw -> $outcome", outcome is Outcome.Failure && outcome.error is AppError.Unknown)
        }
    }

    @Test
    fun `session errors require login`() {
        assertTrue(AppError.SessionExpired.requiresLogin)
        assertTrue(AppError.NotLoggedIn.requiresLogin)
        assertTrue(AppError.ChallengeRequired.requiresLogin)
        assertTrue(!AppError.Network.requiresLogin)
        assertTrue(!AppError.RateLimited.requiresLogin)
    }

    @Test
    fun `download_post data`() {
        val raw = """{"ok":true,"data":{"id":"ABC","owner":null,"files":[
            {"path":"/c/ABC_1.jpg","name":"ABC_1.jpg","kind":"image","mime":"image/jpeg"},
            {"path":"/c/ABC_2.mp4","name":"ABC_2.mp4","kind":"video","mime":"video/mp4"}]}}"""
        val post = (BridgeEnvelope.decode(raw, BridgeEnvelope::downloadedBatch) as Outcome.Success).value
        assertEquals("ABC", post.id)
        assertEquals(null, post.owner)
        assertEquals(listOf("ABC_1.jpg", "ABC_2.mp4"), post.files.map { it.displayName })
        assertEquals(
            listOf(com.yzinsta.downloader.data.download.MediaKind.IMAGE, com.yzinsta.downloader.data.download.MediaKind.VIDEO),
            post.files.map { it.kind },
        )
        assertEquals("/c/ABC_2.mp4", post.files[1].file.path)
    }

    @Test
    fun `each file carries its account folder`() {
        val raw = """{"ok":true,"data":{"id":"179","owner":"zeynep","files":[
            {"path":"/c/zeynep/a.jpg","name":"a.jpg","owner":"zeynep","kind":"image","mime":"image/jpeg"},
            {"path":"/c/b.jpg","name":"b.jpg","kind":"image","mime":"image/jpeg"}]}}"""
        val batch = (BridgeEnvelope.decode(raw, BridgeEnvelope::downloadedBatch) as Outcome.Success).value
        assertEquals(listOf("zeynep", "zeynep"), batch.files.map { it.album }) // missing -> batch owner

        val noOwner = """{"ok":true,"data":{"id":"1","owner":null,"files":[
            {"path":"/c/b.jpg","name":"b.jpg","kind":"image","mime":"image/jpeg"}]}}"""
        val unknown = (BridgeEnvelope.decode(noOwner, BridgeEnvelope::downloadedBatch) as Outcome.Success).value
        assertEquals("instagram", unknown.files.single().album)
    }

    @Test
    fun `profile steps`() {
        fun step(raw: String) = (BridgeEnvelope.decode(raw, BridgeEnvelope::profileStep) as Outcome.Success).value
        val downloaded = step("""{"ok":true,"data":{"status":"downloaded","downloaded":3,"skipped":1,"failed":0,
            "section":"posts","position":4,"expected":20,"id":"ABC","kind":"post","files":[
            {"path":"/c/z/a.jpg","name":"a.jpg","owner":"zeynep","kind":"image","mime":"image/jpeg"}]}}""")
        downloaded as com.yzinsta.downloader.data.profile.ProfileStep.Downloaded
        assertEquals("ABC", downloaded.itemId)
        assertEquals(com.yzinsta.downloader.data.profile.ProfileStep.Position("posts", 4, 20), downloaded.at)
        assertEquals(com.yzinsta.downloader.data.profile.ProfileCounters(3, 1, 0), downloaded.counters)
        assertEquals("zeynep", downloaded.files.single().album)

        val skipped = step("""{"ok":true,"data":{"status":"skipped","downloaded":3,"skipped":2,"failed":0,
            "section":"highlights","position":1,"expected":null,"id":"9"}}""")
        assertEquals(null, (skipped as com.yzinsta.downloader.data.profile.ProfileStep.Skipped).at.expected)

        val finished = step("""{"ok":true,"data":{"status":"finished","downloaded":3,"skipped":2,"failed":1}}""")
        assertTrue(finished is com.yzinsta.downloader.data.profile.ProfileStep.Finished)
    }
}
