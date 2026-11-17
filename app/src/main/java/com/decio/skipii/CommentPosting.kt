package com.decio.skipii

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Posts comments to TechniClip Zone (`POST /api/comment`).
 */
object CommentPosting {

    fun forget(videoId: String) {
        CommentFeed.invalidate(videoId)
    }

    suspend fun comment(videoId: String, text: String) = withContext(Dispatchers.IO) {
        if (!Account.signedIn) throw NotSignedInException()
        val form = FormBody.Builder()
            .add("video_id", videoId)
            .add("comment", text.trim())
            .build()
        val req = Request.Builder()
            .url("${TekuZone.BASE_URL}/api/comment")
            .post(form)
            .build()
        Net.client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                val err = runCatching { JSONObject(res.body?.string().orEmpty()).optString("detail") }.getOrNull()
                throw IOException(err?.ifBlank { null } ?: str(R.string.comments_error_cant_post))
            }
        }
        CommentFeed.invalidate(videoId)
    }

    suspend fun reply(videoId: String, @Suppress("UNUSED_PARAMETER") threadId: String, text: String) =
        comment(videoId, text)

    suspend fun setLiked(
        @Suppress("UNUSED_PARAMETER") videoId: String,
        @Suppress("UNUSED_PARAMETER") commentId: String,
        @Suppress("UNUSED_PARAMETER") liked: Boolean,
    ) = Unit
}
