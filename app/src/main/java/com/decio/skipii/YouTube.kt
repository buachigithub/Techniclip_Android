package com.decio.skipii

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

@Immutable
data class VideoRatings(
    val audio: Long = 0,
    val visual: Long = 0,
    val idea: Long = 0,
) {
    val total: Long get() = audio + visual + idea
}

@Immutable
data class RatedCategories(
    val audio: Boolean = false,
    val visual: Boolean = false,
    val idea: Boolean = false,
) {
    val any: Boolean get() = audio || visual || idea
}

@Immutable
data class CollaborationEvent(
    val id: String,
    val title: String,
    val description: String,
    val startDate: String,
    val endDate: String,
    val organizerTwitter: String,
    val materialsInfo: String,
)

@Immutable
data class VideoItem(
    val id: String,
    val title: String,
    val channel: String,
    val channelUrl: String?,
    val thumbnail: String?,
    val avatar: String?,
    val durationSec: Long,
    val views: Long,
    val uploaded: String?,
    val feedback: CardFeedback? = null,
)

@Immutable
data class CardFeedback(val notInterested: String?, val dontRecommend: String?)

@Immutable
data class VideoDetails(
    val item: VideoItem,
    val description: String,
    val likes: Long,
    val subscribers: Long,
    val related: List<VideoItem>,
    val playable: Boolean,
    val quality: String?,
    val qualities: List<Int>,
    val aspect: Float? = null,
    val chapters: List<Chapter> = emptyList(),
    val storyboards: List<Storyboard> = emptyList(),
    val captions: List<CaptionTrack> = emptyList(),
    val ratings: VideoRatings = VideoRatings(),
    val ratedCategories: RatedCategories = RatedCategories(),
    val streamUrl: String? = null,
    val isHls: Boolean = false,
    val sources: List<String> = emptyList(),
    val characters: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val respectTo: String? = null,
)

@Immutable
data class Chapter(val startMs: Long, val title: String)

@Immutable
data class Storyboard(
    val urls: List<String>,
    val frameWidth: Int,
    val frameHeight: Int,
    val columns: Int,
    val rows: Int,
    val count: Int,
    val msPerFrame: Int,
) {
    fun frameAt(ms: Long, durationMs: Long): StoryboardFrame? {
        if (urls.isEmpty() || count <= 0 || columns <= 0 || rows <= 0) return null
        val step = if (msPerFrame > 0) msPerFrame.toLong() else (durationMs / count).coerceAtLeast(1L)
        val n = (ms / step).toInt().coerceIn(0, count - 1)
        val perSheet = columns * rows
        val url = urls.getOrNull(n / perSheet) ?: return null
        val k = n % perSheet
        return StoryboardFrame(url, (k % columns) * frameWidth, (k / columns) * frameHeight, frameWidth, frameHeight)
    }
}

@Immutable
data class StoryboardFrame(val url: String, val x: Int, val y: Int, val width: Int, val height: Int)

@Immutable
data class ChannelDetails(
    val url: String,
    val id: String,
    val name: String,
    val avatar: String?,
    val banner: String?,
    val subscribers: Long,
    val description: String,
    val videosTab: Any? = null,
)

@Immutable
data class Comment(
    val id: String,
    val author: String,
    val channelId: String?,
    val name: String? = null,
    val avatar: String?,
    val text: String,
    val likes: Int,
    val ago: String?,
    val pinned: Boolean,
    val hearted: Boolean,
    val replyCount: Int,
    val repliesToken: String?,
    val liked: Boolean = false,
)

@Immutable
data class CommentsPage(
    val comments: List<Comment>,
    val total: Int,
    val disabled: Boolean,
    val next: String?,
    val chapters: List<Chapter> = emptyList(),
)

/** Resolved stream and metadata entry for a TechniClip Zone video. */
class TekuVideoRecord(
    val id: String,
    val name: String,
    val uploaderName: String,
    val uploaderUsername: String,
    val duration: Long,
    val streamUrl: String,
    val isHls: Boolean,
    val details: VideoDetails,
    val comments: List<Comment>,
    val subscribed: Boolean?,
)

/** Lazily resolves and caches user avatars and display names from `/api/user/{username}` for feeds that only return usernames. */
object UserProfiles {
    val avatars = mutableStateMapOf<String, String>()
    val names = mutableStateMapOf<String, String>()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun note(username: String?, name: String?, avatar: String?) {
        val u = username?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() && it != "unknown" } ?: return
        val n = name?.trim()?.takeIf { it.isNotEmpty() }
        val a = avatar?.trim()?.takeIf { it.isNotEmpty() }
        if (n == null && a == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (n != null && n != u) names[u] = n
            if (a != null) { avatars[u] = a; asked.add(u) }
        } else {
            mainHandler.post {
                if (n != null && n != u) names[u] = n
                if (a != null) { avatars[u] = a; asked.add(u) }
            }
        }
    }

    fun resolve(usernameOrUrl: String?) {
        val raw = usernameOrUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val u = (if ("/" in raw) TekuZone.usernameFromUrl(raw) else raw.removePrefix("@"))
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "unknown" && it != "ゲスト" }
            ?: return
        if (avatars.containsKey(u) || !asked.add(u)) return
        pool.execute {
            runCatching {
                val encoded = URLEncoder.encode(u, "UTF-8")
                val req = Request.Builder()
                    .url("${TekuZone.BASE_URL}/api/user/$encoded")
                    .get()
                    .build()
                Net.client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use
                    val o = JSONObject(res.body?.string().orEmpty())
                    val name = o.optString("name").ifBlank { u }
                    val avatar = TekuZone.absUrl(o.optString("avatar_url").ifBlank { null })
                    mainHandler.post {
                        names[u] = name
                        if (avatar != null) avatars[u] = avatar
                    }
                }
            }.onFailure {
                asked.remove(u)
            }
        }
    }
}

/** Lazily loads and caches character transparent images from `/charapedia` for material/character chips. */
object CharacterImages {
    val images = mutableStateMapOf<String, String>()
    @Volatile
    private var loaded = false
    private val pool = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        pool.execute {
            runCatching {
                val req = Request.Builder()
                    .url("${TekuZone.BASE_URL}/charapedia")
                    .get()
                    .build()
                Net.client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        loaded = false
                        return@use
                    }
                    val html = res.body?.string().orEmpty()
                    val found = HashMap<String, String>()
                    val cardRe = Regex(
                        """<a[^>]*class="[^"]*chara-card[^"]*"[^>]*data-name="([^"]*)"[^>]*data-aliases="([^"]*)"[\s\S]*?<div class="chara-list-thumb">[\s\S]*?<img\s+src="([^"]+)"""",
                        RegexOption.IGNORE_CASE,
                    )
                    cardRe.findAll(html).forEach { m ->
                        val name = m.groupValues[1].trim().lowercase()
                        val aliases = m.groupValues[2].split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                        val imgUrl = TekuZone.absUrl(m.groupValues[3].trim()) ?: return@forEach
                        if (name.isNotEmpty()) found[name] = imgUrl
                        aliases.forEach { alias -> found[alias] = imgUrl }
                    }
                    if (found.isNotEmpty()) {
                        mainHandler.post { images.putAll(found) }
                    }
                }
            }.onFailure {
                loaded = false
            }
        }
    }

    fun urlFor(characterName: String): String? = images[characterName.trim().lowercase()]
}

/** Everything the app asks of TechniClip Zone (てくぞね) goes through here. */
object YouTube {

    fun watchUrl(id: String) = "${TekuZone.BASE_URL}/video/$id"

    suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("${TekuZone.BASE_URL}/api/search?q=$encoded&limit=100")
            .get()
            .build()
        Net.client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val root = JSONObject(res.body?.string().orEmpty())
            val arr = root.optJSONArray("results") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { parseVideoItem(it) }
            }
        }
    }

    fun isMusic(@Suppress("UNUSED_PARAMETER") info: Any?): Boolean = false

    suspend fun details(id: String, @Suppress("UNUSED_PARAMETER") wait: Boolean = true): VideoDetails =
        withContext(Dispatchers.IO) {
            StreamCache.getOrLoad(id).details
        }

    suspend fun channel(url: String): ChannelDetails = withContext(Dispatchers.IO) {
        val username = TekuZone.usernameFromUrl(url) ?: throw IOException("Invalid user URL")
        val encoded = URLEncoder.encode(username, "UTF-8")
        val req = Request.Builder()
            .url("${TekuZone.BASE_URL}/api/user/$encoded")
            .get()
            .build()
        Net.client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val o = JSONObject(res.body?.string().orEmpty())
            val uname = o.optString("username").ifBlank { username }
            val displayName = o.optString("name").ifBlank { uname }
            val avatar = TekuZone.absUrl(o.optString("avatar_url").ifBlank { null })
            UserProfiles.note(uname, displayName, avatar)
            val followers = o.optLong("followers_count", 0L)
            val videosCount = o.optInt("videos_count", 0)
            ChannelDetails(
                url = "${TekuZone.BASE_URL}/user/$uname",
                id = uname,
                name = displayName,
                avatar = avatar,
                banner = null,
                subscribers = followers,
                description = "@$uname ・ ${videosCount}本の動画",
                videosTab = null,
            )
        }
    }

    internal fun parseVideoItem(o: JSONObject): VideoItem? {
        val id = o.optString("id").ifBlank { o.optString("video_id") }.ifBlank { return null }
        val title = o.optString("title").ifBlank { "無題の動画" }
        val primaryAuthorObj = o.optJSONObject("primary_author")
            ?: o.optJSONArray("authors")?.optJSONObject(0)
        val rawAuthor = when (val a = o.opt("author")) {
            is JSONArray -> a.optString(0)
            is String -> a
            else -> ""
        }
        val username = primaryAuthorObj?.optString("username")?.ifBlank { null }
            ?: rawAuthor.ifBlank { "unknown" }
        val displayName = primaryAuthorObj?.optString("name")?.ifBlank { null }
            ?: o.optString("author_name").ifBlank { null }
            ?: UserProfiles.names[username]
            ?: username
        val avatar = TekuZone.absUrl(
            primaryAuthorObj?.optString("avatar_url")?.ifBlank { null }
                ?: o.optString("avatar_url").ifBlank { null }
        ) ?: UserProfiles.avatars[username]
        if (avatar != null) UserProfiles.note(username, displayName, avatar) else UserProfiles.resolve(username)
        val thumbPath = o.optString("thumbnail_path").ifBlank { "/thumbnails/$id.jpg" }
        val views = o.optLong("views", 0L)
        val uploadRaw = o.optString("upload_time").ifBlank { o.optString("upload_time_iso") }.ifBlank { o.optString("watched_at") }
        val uploaded = parseUploadedRelative(uploadRaw) ?: o.optString("upload_time_formatted").ifBlank { null }
        return VideoItem(
            id = id,
            title = title,
            channel = displayName,
            channelUrl = "${TekuZone.BASE_URL}/user/$username",
            thumbnail = TekuZone.absUrl(thumbPath),
            avatar = avatar,
            durationSec = o.optLong("duration", 0L),
            views = views,
            uploaded = uploaded,
        )
    }

    internal fun parseUploadedRelative(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val instant = runCatching {
            when {
                s.endsWith("Z") || '+' in s.drop(10) -> OffsetDateTime.parse(s).toInstant()
                'T' in s -> LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)
                else -> null
            }
        }.getOrNull() ?: return s.substringBefore('T').ifBlank { null }
        return relativeTime(instant)
    }

    fun relativeTime(then: Instant, now: Instant = Instant.now()): String {
        val s = Duration.between(then, now).seconds.coerceAtLeast(0)
        fun ago(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
        return when {
            s < 60 -> "just now"
            s < 3_600 -> ago(s / 60, "minute")
            s < 86_400 -> ago(s / 3_600, "hour")
            s < 7 * 86_400 -> ago(s / 86_400, "day")
            s < 30 * 86_400 -> ago(s / (7 * 86_400), "week")
            s < 365 * 86_400 -> ago(s / (30 * 86_400), "month")
            else -> ago(s / (365 * 86_400), "year")
        }
    }

    fun friendlyError(t: Throwable): String = when (t) {
        is IOException -> str(R.string.player_error_offline)
        else -> str(R.string.player_error_generic)
    }
}

object StreamCache {
    private const val MAX_AGE_MS = 30 * 60 * 1000L

    private class Entry(val record: TekuVideoRecord, val loadedAt: Long) {
        val fresh get() = System.currentTimeMillis() - loadedAt < MAX_AGE_MS
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val inflight = ConcurrentHashMap<String, FutureTask<TekuVideoRecord>>()

    fun invalidate(id: String) { entries.remove(id) }
    fun invalidateAll() = entries.clear()

    fun get(id: String): TekuVideoRecord? = entries[id]?.takeIf { it.fresh }?.record

    fun put(record: TekuVideoRecord) {
        entries[record.id] = Entry(record, System.currentTimeMillis())
    }

    fun getOrLoad(id: String): TekuVideoRecord {
        entries[id]?.takeIf { it.fresh }?.let { return it.record }
        val mine = FutureTask {
            fetchRecord(id).also { record ->
                entries[id] = Entry(record, System.currentTimeMillis())
                Net.noteMediaHost(record.streamUrl)
            }
        }
        val running = inflight.putIfAbsent(id, mine)
        val task = running ?: mine.also { it.run() }
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            if (running == null) inflight.remove(id, mine)
        }
    }

    private fun fetchRecord(id: String): TekuVideoRecord {
        val encoded = URLEncoder.encode(id, "UTF-8")
        val req = Request.Builder()
            .url("${TekuZone.BASE_URL}/api/video/$encoded")
            .get()
            .build()
        Net.client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} for video $id")
            val o = JSONObject(res.body?.string().orEmpty())
            return parseRecordFromVideoApi(id, o)
        }
    }

    internal fun parseRecordFromVideoApi(fallbackId: String, o: JSONObject): TekuVideoRecord {
        val id = o.optString("id").ifBlank { o.optString("video_id") }.ifBlank { fallbackId }
        val title = o.optString("title").ifBlank { "無題の動画" }
        val authorObj = o.optJSONArray("authors")?.optJSONObject(0) ?: o.optJSONObject("primary_author")
        val rawAuthor = when (val a = o.opt("author")) {
            is JSONArray -> a.optString(0)
            is String -> a
            else -> ""
        }
        val username = authorObj?.optString("username")?.ifBlank { null } ?: rawAuthor.ifBlank { "unknown" }
        val displayName = authorObj?.optString("name")?.ifBlank { null } ?: username
        val avatar = TekuZone.absUrl(authorObj?.optString("avatar_url")?.ifBlank { null })
        UserProfiles.note(username, displayName, avatar)
        val isFollowing = if (authorObj?.has("is_following") == true) authorObj.optBoolean("is_following") else null

        val isHls = o.optBoolean("is_hls", false) && o.optString("hls_path").isNotBlank()
        val hlsPath = o.optString("hls_path")
        val filePath = o.optString("file_path").ifBlank { "/uploads/$id.mp4" }
        val streamUrl = TekuZone.absUrl(if (isHls) hlsPath else filePath) ?: "${TekuZone.BASE_URL}/uploads/$id.mp4"
        val thumbUrl = TekuZone.absUrl(o.optString("thumbnail_path").ifBlank { "/thumbnails/$id.jpg" })

        val ratingsObj = o.optJSONObject("ratings")
        val ratings = VideoRatings(
            audio = ratingsObj?.optLong("audio", 0L) ?: 0L,
            visual = ratingsObj?.optLong("visual", 0L) ?: 0L,
            idea = ratingsObj?.optLong("idea", 0L) ?: 0L,
        )
        val ratedObj = o.optJSONObject("rated_categories") ?: o.optJSONObject("user_rated")
        val rated = RatedCategories(
            audio = ratedObj?.optBoolean("audio", false) ?: false,
            visual = ratedObj?.optBoolean("visual", false) ?: false,
            idea = ratedObj?.optBoolean("idea", false) ?: false,
        )

        val baseDesc = o.optString("description").trim()
        val respect = o.optString("respect_to").trim()
        val sources = o.optJSONArray("sources")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } } }.orEmpty()
        val characters = o.optJSONArray("characters")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } } }.orEmpty()
        val tags = o.optJSONArray("tags")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } } }.orEmpty()
        val fullDescription = buildString {
            if (baseDesc.isNotEmpty()) append(baseDesc)
            if (respect.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("リスペクト: ").append(respect)
            }
            if (sources.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("原曲・ソース: ").append(sources.joinToString(", "))
            }
            if (characters.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("素材: ").append(characters.joinToString(", "))
            }
            if (tags.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("タグ: ").append(tags.joinToString(" ") { "#$it" })
            }
        }

        val relatedArr = o.optJSONArray("recommended") ?: JSONArray()
        val related = (0 until relatedArr.length()).mapNotNull { i ->
            relatedArr.optJSONObject(i)?.let { YouTube.parseVideoItem(it) }
        }

        val commentsArr = o.optJSONArray("comments") ?: JSONArray()
        val comments = (0 until commentsArr.length()).mapNotNull { i ->
            val c = commentsArr.optJSONObject(i) ?: return@mapNotNull null
            val uInfo = c.optJSONObject("user_info")
            val cUser = uInfo?.optString("username")?.ifBlank { null }
                ?: c.optString("user").ifBlank { c.optString("author") }.ifBlank { "ゲスト" }
            val cName = uInfo?.optString("name")?.ifBlank { null }
                ?: c.optString("display_name").ifBlank { null }
                ?: UserProfiles.names[cUser]
                ?: cUser
            val cAvatar = TekuZone.absUrl(
                uInfo?.optString("avatar_url")?.ifBlank { null }
                    ?: c.optString("avatar_url").ifBlank { null }
            ) ?: UserProfiles.avatars[cUser]
            if (cAvatar != null) UserProfiles.note(cUser, cName, cAvatar) else UserProfiles.resolve(cUser)
            val cText = c.optString("text").ifBlank { c.optString("comment") }.trim()
            if (cText.isEmpty()) return@mapNotNull null
            val cTs = c.optString("timestamp").ifBlank { c.optString("timestamp_iso") }
            Comment(
                id = c.optString("id").ifBlank { "comment-$id-$i" },
                author = "@$cUser",
                channelId = cUser,
                name = cName,
                avatar = cAvatar,
                text = cText,
                likes = c.optInt("advert_points", 0),
                ago = YouTube.parseUploadedRelative(cTs) ?: c.optString("formatted_timestamp").ifBlank { null },
                pinned = c.optBoolean("is_advert_comment", false),
                hearted = c.optBoolean("is_support_comment", false),
                replyCount = 0,
                repliesToken = null,
                liked = false,
            )
        }

        val item = VideoItem(
            id = id,
            title = title,
            channel = displayName,
            channelUrl = "${TekuZone.BASE_URL}/user/$username",
            thumbnail = thumbUrl,
            avatar = avatar,
            durationSec = o.optLong("duration", 0L),
            views = o.optLong("views", 0L),
            uploaded = YouTube.parseUploadedRelative(o.optString("upload_time")) ?: o.optString("upload_time_formatted").ifBlank { null },
        )

        val details = VideoDetails(
            item = item,
            description = fullDescription,
            likes = ratings.total,
            subscribers = -1L,
            related = related,
            playable = streamUrl.isNotBlank(),
            quality = if (isHls) "HLS Auto" else "Original",
            qualities = emptyList(),
            aspect = null,
            chapters = emptyList(),
            storyboards = emptyList(),
            captions = emptyList(),
            ratings = ratings,
            ratedCategories = rated,
            streamUrl = streamUrl,
            isHls = isHls,
            sources = sources,
            characters = characters,
            tags = tags,
            respectTo = respect.ifBlank { null },
        )

        return TekuVideoRecord(
            id = id,
            name = title,
            uploaderName = displayName,
            uploaderUsername = username,
            duration = item.durationSec,
            streamUrl = streamUrl,
            isHls = isHls,
            details = details,
            comments = comments,
            subscribed = isFollowing,
        )
    }

    private val prefetcher = Executors.newSingleThreadExecutor()

    fun prefetch(id: String) {
        if (get(id) != null || inflight.containsKey(id)) return
        prefetcher.execute { runCatching { getOrLoad(id) } }
    }
}

object BotCheck {
    var waiting by mutableStateOf<String?>(null)
        private set

    fun noteRefused(@Suppress("UNUSED_PARAMETER") id: String) {}
    fun refusedSince(@Suppress("UNUSED_PARAMETER") id: String, @Suppress("UNUSED_PARAMETER") since: Long) = false

    suspend fun <T> retrying(@Suppress("UNUSED_PARAMETER") id: String, load: () -> T): T = load()
    fun <T> retryingBlocking(@Suppress("UNUSED_PARAMETER") id: String, load: () -> T): T = runBlocking { load() }
    fun <T> withoutWaiting(@Suppress("UNUSED_PARAMETER") id: String, load: () -> T): T = load()
}

object QualityPreference {
    private const val KEY = "quality_height"
    private var prefs: SharedPreferences? = null

    @Volatile
    var height: Int? = null
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        height = prefs?.getInt(KEY, 0)?.takeIf { it > 0 }
    }

    fun set(value: Int?) {
        height = value
        prefs?.edit()?.putInt(KEY, value ?: 0)?.apply()
    }
}

object StreamSelection {
    data class Picked(val playedHeight: Int? = null) {
        val isPlayable get() = true
        val usesSeparate get() = false
    }

    fun pick(@Suppress("UNUSED_PARAMETER") info: Any?): Picked = Picked(null)
    fun heights(@Suppress("UNUSED_PARAMETER") info: Any?): List<Int> = emptyList()
}
