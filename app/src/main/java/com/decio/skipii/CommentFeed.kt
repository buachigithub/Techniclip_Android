package com.decio.skipii

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads comments for a TechniClip Zone video from `/api/video/{video_id}` (shared via [StreamCache]).
 */
object CommentFeed {
    private class Ahead(val at: Long, val page: kotlinx.coroutines.Deferred<CommentsPage>)
    private val ahead = object : LinkedHashMap<String, Ahead>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Ahead>?) = size > 8
    }
    private val aheadScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private const val AheadMs = 5 * 60_000L

    fun prefetch(videoId: String) = synchronized(ahead) {
        val known = ahead[videoId]
        if (known != null && System.currentTimeMillis() - known.at < AheadMs && !known.page.isCancelled) return@synchronized
        ahead[videoId] = Ahead(System.currentTimeMillis(), aheadScope.async { load(videoId) })
    }

    fun invalidate(videoId: String) = synchronized(ahead) {
        ahead.remove(videoId)
        StreamCache.invalidate(videoId)
    }

    suspend fun first(videoId: String): CommentsPage {
        val early = synchronized(ahead) { ahead[videoId]?.takeIf { System.currentTimeMillis() - it.at < AheadMs } }
        if (early != null) {
            try {
                return early.page.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                synchronized(ahead) { ahead.remove(videoId) }
            }
        }
        return load(videoId)
    }

    private suspend fun load(videoId: String): CommentsPage = withContext(Dispatchers.IO) {
        val record = StreamCache.getOrLoad(videoId)
        CommentsPage(
            comments = record.comments,
            total = record.comments.size,
            disabled = false,
            next = null,
            chapters = emptyList(),
        )
    }

    internal fun parseChapters(@Suppress("UNUSED_PARAMETER") page: JSONObject): List<Chapter> = emptyList()

    suspend fun more(@Suppress("UNUSED_PARAMETER") token: String): CommentsPage =
        CommentsPage(emptyList(), total = 0, disabled = false, next = null)
}

object ChannelNames {
    val known = mutableStateMapOf<String, String>()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(4)

    fun resolve(scope: CoroutineScope, channelIds: Collection<String>) {
        channelIds.filter { asked.add(it) }.forEach { id ->
            scope.launch {
                val info = gate.withPermit { withContext(Dispatchers.IO) { runCatching { YouTube.channel(id) } } }
                info.onSuccess { known[id] = it.name }
                    .onFailure { asked.remove(id) }
            }
        }
    }
}

fun Comment.displayName(): String = name ?: channelId?.let { ChannelNames.known[it] } ?: author
