package com.decio.skipii

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@OptIn(UnstableApi::class)
object ShortsBank {
    const val Size = 10

    class Track(val url: String, val manifest: String = "", val bytes: Long = 0L)

    class Entry(val item: VideoItem, val aspect: Float, val video: Track, val audio: Track) {
        val urls get() = listOf(video.url).distinct()
    }

    private var cache: SimpleCache? = null
    private var indexFile: File? = null
    private val entries = LinkedHashMap<String, Entry>()
    var next: String? = null
        private set

    private val upstream by lazy { OkHttpDataSource.Factory(Net.client).setUserAgent(Net.USER_AGENT) }

    fun init(context: Context) {
        runCatching {
            cache = SimpleCache(File(context.cacheDir, "shorts-bank"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
        }.onFailure { Log.w("Skipii", "shorts bank: no media cache", it) }
        indexFile = File(context.filesDir, "shorts_bank.json")
        load()
        Log.i("Skipii", "shorts bank: ${entries.size} shorts ready from last time")
    }

    fun entries(): List<Entry> = synchronized(entries) { entries.values.toList() }
    fun has(id: String) = synchronized(entries) { entries.containsKey(id) }
    fun get(id: String): Entry? = synchronized(entries) { entries[id] }
    val size get() = synchronized(entries) { entries.size }

    fun forget(id: String) {
        val gone = synchronized(entries) { entries.remove(id) } ?: return
        Log.i("Skipii", "shorts bank: ${gone.item.id} seen, forgotten (${size} left)")
        save()
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
        next = null
        save()
        cleanup(emptySet())
        Log.i("Skipii", "shorts bank: cleared (Shorts turned off)")
    }

    fun setNext(token: String?) {
        if (token == next) return
        next = token
        save()
    }

    fun tracks(info: TekuVideoRecord): Pair<Track, Track>? {
        val url = info.streamUrl.takeIf { it.isNotBlank() } ?: return null
        val t = Track(url = url, manifest = if (info.isHls) "hls" else "mp4", bytes = 0L)
        return t to t
    }

    fun mediaSource(video: Track, @Suppress("UNUSED_PARAMETER") audio: Track, mediaItem: MediaItem): MediaSource {
        val resolved = mediaItem.buildUpon().setUri(Uri.parse(video.url)).build()
        return DefaultMediaSourceFactory(dataSourceFactory()).createMediaSource(resolved)
    }

    private fun dataSourceFactory(): androidx.media3.datasource.DataSource.Factory {
        val c = cache ?: return upstream
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    suspend fun download(item: VideoItem, aspect: Float, video: Track, audio: Track) = withContext(Dispatchers.IO) {
        val c = cache ?: error("no media cache")
        val startedAt = android.os.SystemClock.elapsedRealtime()
        if (!video.url.endsWith(".m3u8")) {
            val source = CacheDataSource.Factory().setCache(c).setUpstreamDataSourceFactory(upstream).createDataSource()
            runCatching {
                CacheWriter(source, DataSpec(Uri.parse(video.url)), null, null).cache()
            }
        }
        val entry = Entry(item, aspect, video, audio)
        synchronized(entries) { entries[item.id] = entry }
        save()
        Log.i(
            "Skipii",
            "shorts bank: ${item.id} banked in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms; $size ready",
        )
    }

    fun cleanup(inUse: Set<String>) {
        val c = cache ?: return
        val keep = entries().flatMapTo(HashSet()) { it.urls } + inUse
        c.keys.filter { it !in keep }.forEach { runCatching { c.removeResource(it) } }
    }

    private fun save() {
        val file = indexFile ?: return
        val json = JSONObject()
            .put("next", next)
            .put("entries", JSONArray().apply { entries().forEach { put(it.toJson()) } })
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
        }.onFailure { Log.w("Skipii", "shorts bank: couldn't save", it) }
    }

    private fun load() {
        val file = indexFile ?: return
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            next = json.optString("next").ifBlank { null }
            val list = json.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until list.length()) {
                val e = entryFrom(list.getJSONObject(i)) ?: continue
                if (complete(e)) entries[e.item.id] = e
            }
        }.onFailure { Log.w("Skipii", "shorts bank: unreadable index; starting empty", it) }
    }

    private fun complete(e: Entry): Boolean = e.video.url.isNotBlank()

    private fun Entry.toJson() = JSONObject()
        .put("id", item.id).put("title", item.title).put("channel", item.channel)
        .put("channelUrl", item.channelUrl).put("thumbnail", item.thumbnail).put("avatar", item.avatar)
        .put("durationSec", item.durationSec).put("aspect", aspect.toDouble())
        .put("video", video.toJson()).put("audio", audio.toJson())

    private fun Track.toJson() = JSONObject().put("url", url).put("manifest", manifest).put("bytes", bytes)

    private fun entryFrom(o: JSONObject): Entry? = runCatching {
        fun track(t: JSONObject) = Track(t.getString("url"), t.optString("manifest"), t.optLong("bytes"))
        Entry(
            item = VideoItem(
                id = o.getString("id"), title = o.optString("title"), channel = o.optString("channel"),
                channelUrl = o.optString("channelUrl").ifBlank { null }, thumbnail = o.optString("thumbnail").ifBlank { null },
                avatar = o.optString("avatar").ifBlank { null }, durationSec = o.optLong("durationSec"), views = 0, uploaded = null,
            ),
            aspect = o.optDouble("aspect", 9.0 / 16.0).toFloat(),
            video = track(o.getJSONObject("video")),
            audio = track(o.getJSONObject("audio")),
        )
    }.getOrNull()
}
