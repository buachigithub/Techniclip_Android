package com.decio.skipii

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

@Immutable
data class Profile(val name: String, val handle: String?, val avatar: String?)

@Immutable
data class PlaylistRef(val id: String, val title: String, val thumbnail: String?, val subtitle: String?)

@Immutable
data class PlaylistChoice(val id: String, val title: String, val contains: Boolean?, val privacy: String?)

@Immutable
data class ChannelRef(val id: String, val name: String, val avatar: String?)

@Immutable
data class FeedPage(val videos: List<VideoItem>, val next: String?, val chips: List<FeedChip> = emptyList())

@Immutable
data class FeedChip(val title: String, val token: String?)

@Immutable
data class PlaylistPage(val playlists: List<PlaylistRef>, val next: String?)

enum class ChannelTab(val title: String) { Home("ホーム"), Videos("動画"), Shorts("Shorts"), Playlists("再生リスト") }

sealed interface ShelfLink {
    data class Tab(val tab: ChannelTab) : ShelfLink
    data class Playlist(val ref: PlaylistRef) : ShelfLink
}

@Immutable
data class ChannelShelf(
    val title: String,
    val videos: List<VideoItem> = emptyList(),
    val shorts: List<VideoItem> = emptyList(),
    val playlists: List<PlaylistRef> = emptyList(),
    val channels: List<ChannelRef> = emptyList(),
    val link: ShelfLink? = null,
)

@Immutable
data class ChannelHome(
    val tabs: Map<ChannelTab, String>,
    val featured: VideoItem?,
    val shelves: List<ChannelShelf>,
    val subscribed: Boolean? = null,
)

enum class LikeStatus { Like, Dislike, None }

@Immutable
data class VideoAccountState(
    val like: LikeStatus,
    val subscribed: Boolean?,
    val channelId: String?,
    val bell: Boolean? = null,
    val ratings: VideoRatings = VideoRatings(),
    val rated: RatedCategories = RatedCategories(),
)

data class SubscribedChannel(val ref: ChannelRef, val bell: Boolean?)

class NotSignedInException : IOException("Not signed in")

class YouTubeRefusedException(val code: Int) : IOException(
    if (code == 401 || code == 403) "てくぞねのセッションが無効です ($code)。再ログインしてください。" else "サーバーエラー ($code)",
) {
    override fun getLocalizedMessage(): String =
        if (code == 401 || code == 403) str(R.string.app_error_session_rejected, code.toString())
        else str(R.string.app_error_youtube_returned, code.toString())
}

/**
 * Signed-in TechniClip Zone (てくぞね) session.
 * Stores the `session` cookie encrypted in Android Keystore ([SecureStore]).
 */
object Account {
    var profile by mutableStateOf<Profile?>(null)
        private set
    val signedIn get() = profile != null

    fun isSelf(usernameOrUrl: String?): Boolean {
        val myHandle = profile?.handle?.removePrefix("@")?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val raw = usernameOrUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val target = (if ("/" in raw) TekuZone.usernameFromUrl(raw) else raw.removePrefix("@"))?.trim() ?: return false
        return myHandle.equals(target, ignoreCase = true)
    }

    private var store: SecureStore? = null
    private val cookies = linkedMapOf<String, String>()
    private var clientVersion = "1.0.0"
    private var visitorData: String? = null

    fun init(context: Context) {
        val s = SecureStore(context, "account").also { store = it }
        s.get(KEY_COOKIES)?.let { parseCookieHeader(it, cookies) }
        val name = s.get(KEY_NAME)
        if (name != null && cookies.isNotEmpty()) {
            profile = Profile(name, s.get(KEY_HANDLE), s.get(KEY_AVATAR))
        }
    }

    /** True once the WebView's cookies contain a TechniClip Zone `session` cookie. */
    fun looksSignedIn(cookieHeader: String?): Boolean {
        val names = cookieHeader.orEmpty().split(';').map { it.substringBefore('=').trim() }.toSet()
        return "session" in names
    }

    /** Direct username/password sign-in against `/api/auth/login`. */
    suspend fun loginWithPassword(username: String, password: String) = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("username", username.trim())
            .add("password", password)
            .build()
        val req = Request.Builder()
            .url("$ORIGIN/api/auth/login")
            .post(form)
            .build()
        Net.client.newCall(req).execute().use { res ->
            absorbSetCookies(res.headers("Set-Cookie"))
            if (!res.isSuccessful) {
                val msg = runCatching { JSONObject(res.body?.string().orEmpty()).optString("detail") }.getOrNull()
                throw IOException(msg?.ifBlank { null } ?: "IDまたはパスワードが正しくありません")
            }
        }
        persistCookies()
        fetchAndStoreProfile()
    }

    /** Takes over the WebView session: stores the cookies, then loads `/api/user/me`. */
    suspend fun completeSignIn(cookieHeader: String) = withContext(Dispatchers.IO) {
        synchronized(cookies) {
            cookies.clear()
            parseCookieHeader(cookieHeader, cookies)
        }
        persistCookies()
        fetchAndStoreProfile()
    }

    private suspend fun fetchAndStoreProfile() {
        val fallbackName = str(R.string.app_account_fallback_name)
        val req = Request.Builder().url("$ORIGIN/api/user/me").get().build()
        val p = Net.client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw YouTubeRefusedException(res.code)
            val o = JSONObject(res.body?.string().orEmpty())
            val uname = o.optString("username").ifBlank { null }
            val name = o.optString("name").ifBlank { uname ?: fallbackName }
            val avatar = TekuZone.absUrl(o.optString("avatar_url").ifBlank { null })
            Profile(name = name, handle = uname?.let { "@$it" }, avatar = avatar)
        }
        store?.apply {
            put(KEY_NAME, p.name)
            put(KEY_HANDLE, p.handle)
            put(KEY_AVATAR, p.avatar)
        }
        withContext(Dispatchers.Main) { profile = p }
    }

    fun signOut() {
        synchronized(cookies) { cookies.clear() }
        store?.clear()
        profile = null
        Bells.clear()
        runCatching {
            android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        }
    }

    internal fun clientVersion() = clientVersion
    internal fun visitorData() = visitorData

    internal fun cookieHeader(): String = synchronized(cookies) {
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    internal fun authorization(@Suppress("UNUSED_PARAMETER") origin: String = ORIGIN): String? =
        if (signedIn) "Session" else null

    internal fun absorbSetCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        var changed = false
        synchronized(cookies) {
            setCookies.forEach { header ->
                val pair = header.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isEmpty()) return@forEach
                if (cookies[name] != value) { cookies[name] = value; changed = true }
            }
        }
        if (changed) persistCookies()
    }

    private fun persistCookies() = store?.put(KEY_COOKIES, cookieHeader())

    private fun parseCookieHeader(header: String, into: MutableMap<String, String>) {
        header.split(';').forEach { part ->
            val name = part.substringBefore('=').trim()
            if (name.isNotEmpty()) into[name] = part.substringAfter('=', "").trim()
        }
    }

    const val ORIGIN = TekuZone.BASE_URL
    private const val KEY_COOKIES = "cookies"
    private const val KEY_NAME = "name"
    private const val KEY_HANDLE = "handle"
    private const val KEY_AVATAR = "avatar"
}

/**
 * TechniClip Zone API client replacing YouTube's internal API.
 */
object InnerTube {

    internal fun getJson(path: String, requireAuth: Boolean = false): JSONObject {
        if (requireAuth && !Account.signedIn) throw NotSignedInException()
        val url = TekuZone.absUrl(path) ?: "${TekuZone.BASE_URL}/$path"
        val req = Request.Builder().url(url).get().build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw YouTubeRefusedException(r.code)
            return JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
        }
    }

    internal fun getJsonArray(path: String, requireAuth: Boolean = false): JSONArray {
        if (requireAuth && !Account.signedIn) throw NotSignedInException()
        val url = TekuZone.absUrl(path) ?: "${TekuZone.BASE_URL}/$path"
        val req = Request.Builder().url(url).get().build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw YouTubeRefusedException(r.code)
            return JSONArray(r.body?.string().orEmpty().ifBlank { "[]" })
        }
    }

    internal fun postAny(endpoint: String, body: JSONObject): JSONObject = body
    internal fun post(endpoint: String, body: JSONObject, anonymous: Boolean = false): JSONObject = body

    // ── Home & Feeds ───────────────────────────────────────────────────────────

    suspend fun home(next: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val defaultChips = mutableListOf(
            FeedChip("すべて", null),
            FeedChip("新着", "new"),
            FeedChip("週間ランキング", "weekly"),
            FeedChip("音声ランキング", "rank:audio"),
            FeedChip("映像ランキング", "rank:visual"),
            FeedChip("発想ランキング", "rank:idea"),
        )

        when {
            next == null -> {
                // Load /api/home for popular tag chips + featured videos, and /api/search for full list
                val homeObj = runCatching { getJson("/api/home") }.getOrNull()
                homeObj?.optJSONArray("popular_tags_sections")?.let { sections ->
                    for (i in 0 until sections.length()) {
                        val tag = sections.optJSONObject(i)?.optString("tag")?.trim()?.ifBlank { null } ?: continue
                        defaultChips += FeedChip("#$tag", "tag:$tag")
                    }
                }
                val searchObj = getJson("/api/search?limit=50&offset=0")
                val list = parseSearchList(searchObj)
                val total = searchObj.optInt("total", list.size)
                val nextToken = if (total > 50) "offset:50" else null
                FeedPage(videos = list, next = nextToken, chips = defaultChips)
            }
            next.startsWith("offset:") -> {
                val offset = next.removePrefix("offset:").toIntOrNull() ?: 0
                val searchObj = getJson("/api/search?limit=50&offset=$offset")
                val list = parseSearchList(searchObj)
                val total = searchObj.optInt("total", offset + list.size)
                val nextToken = if (offset + 50 < total && list.isNotEmpty()) "offset:${offset + 50}" else null
                FeedPage(videos = list, next = nextToken)
            }
            next == "new" -> {
                val homeObj = runCatching { getJson("/api/home") }.getOrNull()
                val arr = homeObj?.optJSONArray("new_videos") ?: JSONArray()
                val fromHome = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(YouTube::parseVideoItem) }
                val searchList = parseSearchList(getJson("/api/search?limit=40&offset=0"))
                val combined = (fromHome + searchList).distinctBy { it.id }
                FeedPage(videos = combined, next = "offset:40")
            }
            next == "weekly" -> {
                val root = getJson("/api/ranking/weekly?limit=50")
                val arr = root.optJSONArray("weekly") ?: JSONArray()
                val videos = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    parseRankingItem(o, o.optLong("total_views", o.optLong("weekly_views", o.optLong("views", 0L))))
                }
                FeedPage(videos = videos, next = null)
            }
            next.startsWith("rank:") -> {
                val cat = next.removePrefix("rank:")
                val root = getJson("/api/ranking?category=$cat&limit=60")
                val arr = root.optJSONArray(cat) ?: JSONArray()
                val videos = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    parseRankingItem(o, o.optLong("views", 0L))
                }
                FeedPage(videos = videos, next = null)
            }
            next.startsWith("tag:") -> {
                val tag = next.removePrefix("tag:")
                val encoded = URLEncoder.encode("タグ:$tag", "UTF-8")
                val searchObj = getJson("/api/search?q=$encoded&limit=60")
                FeedPage(videos = parseSearchList(searchObj), next = null)
            }
            else -> FeedPage(emptyList(), null)
        }
    }

    private fun parseSearchList(searchObj: JSONObject): List<VideoItem> {
        val arr = searchObj.optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(YouTube::parseVideoItem)
        }
    }

    private fun parseRankingItem(o: JSONObject, views: Long): VideoItem? {
        val id = o.optString("video_id").ifBlank { o.optString("id") }.ifBlank { return null }
        val rawAuthor = when (val a = o.opt("author")) {
            is JSONArray -> a.optString(0)
            is String -> a
            else -> ""
        }
        val authorName = o.optString("author_name").ifBlank { UserProfiles.names[rawAuthor] ?: rawAuthor }.ifBlank { "Unknown" }
        val username = rawAuthor.ifBlank { authorName }
        val avatar = TekuZone.absUrl(o.optString("avatar_url").ifBlank { null }) ?: UserProfiles.avatars[username]
        if (avatar != null) UserProfiles.note(username, authorName, avatar) else UserProfiles.resolve(username)
        return VideoItem(
            id = id,
            title = o.optString("title").ifBlank { "無題の動画" },
            channel = authorName,
            channelUrl = "${TekuZone.BASE_URL}/user/$username",
            thumbnail = TekuZone.absUrl(o.optString("thumbnail_path").ifBlank { "/thumbnails/$id.jpg" }),
            avatar = avatar,
            durationSec = o.optLong("duration", 0L),
            views = views,
            uploaded = YouTube.parseUploadedRelative(o.optString("upload_time")),
        )
    }

    // ── Channel / User Pages ───────────────────────────────────────────────────

    suspend fun channelVideos(channelId: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val uname = TekuZone.usernameFromUrl(channelId) ?: channelId
        val encoded = URLEncoder.encode(uname, "UTF-8")
        val o = getJson("/api/user/$encoded")
        val displayName = o.optString("name").ifBlank { uname }
        val avatar = TekuZone.absUrl(o.optString("avatar_url").ifBlank { null })
        val arr = o.optJSONArray("videos") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            val v = arr.optJSONObject(i) ?: return@mapNotNull null
            YouTube.parseVideoItem(v)?.copy(
                channel = displayName,
                channelUrl = "${TekuZone.BASE_URL}/user/$uname",
                avatar = avatar,
            )
        }
        val sorted = if (continuation == "popular") list.sortedByDescending { it.views } else list
        val chips = listOf(
            FeedChip("新着順", null),
            FeedChip("人気順", "popular"),
        )
        FeedPage(videos = sorted, next = null, chips = if (continuation == null) chips else emptyList())
    }

    suspend fun channelHome(channelId: String, name: String?, avatar: String?): ChannelHome = withContext(Dispatchers.IO) {
        val uname = TekuZone.usernameFromUrl(channelId) ?: channelId
        val encoded = URLEncoder.encode(uname, "UTF-8")
        val o = getJson("/api/user/$encoded")
        val displayName = o.optString("name").ifBlank { name ?: uname }
        val userAvatar = TekuZone.absUrl(o.optString("avatar_url").ifBlank { null }) ?: avatar
        val isFollowing = if (Account.signedIn && o.has("is_following")) o.optBoolean("is_following") else null

        val arr = o.optJSONArray("videos") ?: JSONArray()
        val videos = (0 until arr.length()).mapNotNull { i ->
            val v = arr.optJSONObject(i) ?: return@mapNotNull null
            YouTube.parseVideoItem(v)?.copy(
                channel = displayName,
                channelUrl = "${TekuZone.BASE_URL}/user/$uname",
                avatar = userAvatar,
            )
        }

        val shelves = buildList {
            if (videos.isNotEmpty()) {
                add(ChannelShelf(title = "新着動画", videos = videos.take(10), link = ShelfLink.Tab(ChannelTab.Videos)))
            }
            if (videos.size > 2) {
                add(ChannelShelf(title = "人気の動画", videos = videos.sortedByDescending { it.views }.take(10)))
            }
        }
        val tabs = linkedMapOf(
            ChannelTab.Home to "home",
            ChannelTab.Videos to "videos",
        )
        if (Account.signedIn) {
            Bells.learn(uname, isFollowing, false, displayName, userAvatar)
        }
        ChannelHome(
            tabs = tabs,
            featured = videos.firstOrNull(),
            shelves = shelves,
            subscribed = isFollowing,
        )
    }

    suspend fun channelShorts(channelId: String, @Suppress("UNUSED_PARAMETER") params: String, @Suppress("UNUSED_PARAMETER") continuation: String? = null): FeedPage =
        channelVideos(channelId, null)

    suspend fun channelPlaylists(@Suppress("UNUSED_PARAMETER") channelId: String, @Suppress("UNUSED_PARAMETER") params: String, @Suppress("UNUSED_PARAMETER") continuation: String? = null): PlaylistPage =
        PlaylistPage(emptyList(), null)

    // ── Subscriptions, History & Playlists ─────────────────────────────────────

    suspend fun subscriptionsFeed(@Suppress("UNUSED_PARAMETER") next: String? = null): FeedPage = withContext(Dispatchers.IO) {
        if (!Account.signedIn) throw NotSignedInException()
        val followed = channels()
        if (followed.isEmpty()) return@withContext FeedPage(emptyList(), null)
        val allVideos = ArrayList<VideoItem>()
        for (ch in followed.take(15)) {
            runCatching {
                val page = channelVideos(ch.id)
                allVideos.addAll(page.videos)
            }
        }
        FeedPage(allVideos.distinctBy { it.id }, null)
    }

    suspend fun history(@Suppress("UNUSED_PARAMETER") next: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val o = getJson("/api/watch_history", requireAuth = true)
        val arr = o.optJSONArray("history") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(YouTube::parseVideoItem)
        }.reversed().distinctBy { it.id }
        FeedPage(list, null)
    }

    suspend fun playlist(id: String, next: String? = null): FeedPage = withContext(Dispatchers.IO) {
        when (id) {
            "LL", "RATE_AUDIO", "RATE_VISUAL", "RATE_IDEA" -> {
                val o = getJson("/api/myratings", requireAuth = true)
                fun parseCategory(key: String): List<VideoItem> {
                    val arr = o.optJSONArray(key) ?: return emptyList()
                    return (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let(YouTube::parseVideoItem)
                    }
                }
                val audioList = parseCategory("audio")
                val visualList = parseCategory("visual")
                val ideaList = parseCategory("idea")
                val fallbackList = parseCategory("rated_videos")
                val filterCat = when (id) {
                    "RATE_AUDIO" -> "audio"
                    "RATE_VISUAL" -> "visual"
                    "RATE_IDEA" -> "idea"
                    else -> next
                }
                val list = when (filterCat) {
                    "audio" -> audioList
                    "visual" -> visualList
                    "idea" -> ideaList
                    else -> (audioList + visualList + ideaList + fallbackList).distinctBy { it.id }
                }
                val chips = if (id == "LL" && next == null) {
                    listOf(
                        FeedChip("すべて", null),
                        FeedChip("音声 (${audioList.size})", "audio"),
                        FeedChip("映像 (${visualList.size})", "visual"),
                        FeedChip("発想 (${ideaList.size})", "idea"),
                    )
                } else emptyList()
                FeedPage(videos = list, next = null, chips = chips)
            }
            else -> {
                // First try /api/playlist/{id}, fallback to /api/playlists/my
                val list = runCatching {
                    val o = getJson("/api/playlist/${URLEncoder.encode(id, "UTF-8")}", requireAuth = true)
                    val arr = o.optJSONArray("videos") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(YouTube::parseVideoItem) }
                }.getOrNull()?.takeIf { it.isNotEmpty() } ?: run {
                    val arr = getJsonArray("/api/playlists/my?video_id=_", requireAuth = true)
                    val matching = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
                    val vidArr = matching?.optJSONArray("videos") ?: JSONArray()
                    (0 until vidArr.length()).mapNotNull { idx ->
                        val vid = vidArr.optString(idx).ifBlank { return@mapNotNull null }
                        runCatching { YouTube.details(vid).item }.getOrNull()
                    }
                }
                FeedPage(list, null)
            }
        }
    }

    suspend fun channels(): List<ChannelRef> = withContext(Dispatchers.IO) {
        if (!Account.signedIn) throw NotSignedInException()
        val out = LinkedHashMap<String, ChannelRef>()
        // 1. Check /api/user/me if it includes following
        runCatching {
            val me = getJson("/api/user/me", requireAuth = true)
            val arr = me.optJSONArray("following")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    when (item) {
                        is JSONObject -> {
                            val u = item.optString("username").ifBlank { continue }
                            out[u] = ChannelRef(u, item.optString("name").ifBlank { u }, TekuZone.absUrl(item.optString("avatar_url")))
                        }
                        is String -> if (item.isNotBlank()) out[item] = ChannelRef(item, item, null)
                    }
                }
            }
        }
        // 2. Fallback: parse /following HTML page links (/user/{username})
        if (out.isEmpty()) {
            runCatching {
                val req = Request.Builder().url("${TekuZone.BASE_URL}/following").get().build()
                Net.client.newCall(req).execute().use { r ->
                    val html = r.body?.string().orEmpty()
                    val myHandle = Account.profile?.handle?.removePrefix("@")
                    Regex("""href=["'](?:/renewed)?/user/([^"'/?#]+)["']""").findAll(html).forEach { m ->
                        val u = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
                        if (u.isNotBlank() && u != myHandle && ! out.containsKey(u)) {
                            val d = runCatching { YouTube.channel(u) }.getOrNull()
                            out[u] = ChannelRef(u, d?.name ?: u, d?.avatar)
                        }
                    }
                }
            }
        }
        out.values.toList()
    }

    suspend fun playlists(): List<PlaylistRef> = withContext(Dispatchers.IO) {
        if (!Account.signedIn) throw NotSignedInException()
        val out = LinkedHashMap<String, PlaylistRef>()
        runCatching {
            val arr = getJsonArray("/api/playlists/my?video_id=_", requireAuth = true)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { continue }
                val name = o.optString("name").ifBlank { "再生リスト" }
                val vids = o.optJSONArray("videos")
                val count = vids?.length() ?: 0
                val firstVid = vids?.optString(0)?.ifBlank { null }
                val thumb = firstVid?.let { "${TekuZone.BASE_URL}/thumbnails/$it.jpg" }
                out[id] = PlaylistRef(id, name, thumb, "${count}本の動画")
            }
        }
        if (out.isEmpty()) {
            runCatching {
                val o = getJson("/api/playlists", requireAuth = true)
                val arr = o.optJSONArray("playlists") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val pl = arr.optJSONObject(i) ?: continue
                    val id = pl.optString("id").ifBlank { continue }
                    val count = pl.optInt("video_count", 0)
                    out[id] = PlaylistRef(
                        id = id,
                        title = pl.optString("name").ifBlank { "再生リスト" },
                        thumbnail = TekuZone.absUrl(pl.optString("thumbnail").ifBlank { null }),
                        subtitle = "${count}本の動画",
                    )
                }
            }
        }
        out.values.toList()
    }

    suspend fun videoState(videoId: String): VideoAccountState = withContext(Dispatchers.IO) {
        val record = StreamCache.get(videoId) ?: StreamCache.getOrLoad(videoId)
        val d = record.details
        val isSubscribed = runCatching {
            val u = getJson("/api/user/${URLEncoder.encode(record.uploaderUsername, "UTF-8")}")
            val name = u.optString("name").ifBlank { record.uploaderName }
            val avatar = TekuZone.absUrl(u.optString("avatar_url").ifBlank { null })
            UserProfiles.note(record.uploaderUsername, name, avatar)
            if (u.has("is_following")) u.optBoolean("is_following") else record.subscribed
        }.getOrNull() ?: record.subscribed
        val like = if (d.ratedCategories.any) LikeStatus.Like else LikeStatus.None
        VideoAccountState(
            like = like,
            subscribed = isSubscribed,
            channelId = record.uploaderUsername,
            bell = false,
            ratings = d.ratings,
            rated = d.ratedCategories,
        )
    }

    suspend fun subscriptions(): List<SubscribedChannel>? = withContext(Dispatchers.IO) {
        runCatching { channels().map { SubscribedChannel(it, false) } }.getOrNull()
    }

    // ── Shorts (Standard Shorts feed ONLY; Motto Shorts excluded per user request) ──

    suspend fun shorts(token: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val excludeParam = if (!token.isNullOrBlank()) "&exclude=${URLEncoder.encode(token, "UTF-8")}" else ""
        val arr = getJsonArray("/api/shorts/feed?limit=10$excludeParam")
        val videos = ArrayList<VideoItem>()
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("video_id").ifBlank { o.optString("id") }.ifBlank { continue }
            val record = StreamCache.parseRecordFromVideoApi(id, o)
            StreamCache.put(record)
            videos += record.details.item
            ids += id
        }
        val nextExclude = if (ids.isEmpty()) null else listOfNotNull(token, ids.joinToString(",")).joinToString(",")
        FeedPage(videos = videos, next = nextExclude)
    }

    // ── Writes (Ratings, Subscribe, Playlists) ─────────────────────────────────

    /** Toggles one of the 3 rating categories ("audio", "visual", "idea") via `/api/rate`. */
    suspend fun rateVideo(videoId: String, category: String): Long = withContext(Dispatchers.IO) {
        if (!Account.signedIn) throw NotSignedInException()
        val form = FormBody.Builder()
            .add("video_id", videoId)
            .add("category", category)
            .build()
        val req = Request.Builder()
            .url("${TekuZone.BASE_URL}/api/rate")
            .post(form)
            .build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw YouTubeRefusedException(r.code)
            val json = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
            StreamCache.invalidate(videoId)
            json.optLong("updated_value", -1L)
        }
    }

    suspend fun setLike(videoId: String, @Suppress("UNUSED_PARAMETER") status: LikeStatus) = withContext(Dispatchers.IO) {
        rateVideo(videoId, "idea")
        Unit
    }

    suspend fun setSubscribed(channelId: String, @Suppress("UNUSED_PARAMETER") subscribed: Boolean) = withContext(Dispatchers.IO) {
        if (!Account.signedIn) throw NotSignedInException()
        val target = TekuZone.usernameFromUrl(channelId) ?: channelId
        val form = FormBody.Builder()
            .add("target", target)
            .build()
        val req = Request.Builder()
            .url("${TekuZone.BASE_URL}/api/subscribe")
            .post(form)
            .build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw YouTubeRefusedException(r.code)
        }
    }

    suspend fun setBell(@Suppress("UNUSED_PARAMETER") channelId: String, @Suppress("UNUSED_PARAMETER") all: Boolean) = Unit

    suspend fun advertPoints(): Int = withContext(Dispatchers.IO) {
        if (!Account.signedIn) throw NotSignedInException()
        val json = getJson("/api/advert/user-points", requireAuth = true)
        json.optInt("points", 0)
    }

    suspend fun createAdvert(videoId: String, points: Int, comment: String): String = withContext(Dispatchers.IO) {
        if (!Account.signedIn) throw NotSignedInException()
        val form = FormBody.Builder()
            .add("video_id", videoId)
            .add("points", points.toString())
            .add("comment", comment)
            .build()
        val req = Request.Builder()
            .url("${TekuZone.BASE_URL}/api/advert/create")
            .post(form)
            .build()
        Net.client.newCall(req).execute().use { r ->
            val bodyStr = r.body?.string().orEmpty()
            val json = runCatching { JSONObject(bodyStr) }.getOrNull()
            if (!r.isSuccessful) {
                val detail = json?.optString("detail")?.ifBlank { null }
                throw IOException(detail ?: "広告の出稿に失敗しました (${r.code})")
            }
            json?.optString("message")?.ifBlank { null } ?: "広告を出稿しました！"
        }
    }

    suspend fun activeCollaborations(): List<CollaborationEvent> = withContext(Dispatchers.IO) {
        val arr = getJsonArray("/api/collaborations?status=active")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { o.optString("collab_id") }.ifBlank { return@mapNotNull null }
            CollaborationEvent(
                id = id,
                title = o.optString("title").ifBlank { "合作イベント" },
                description = o.optString("description").trim(),
                startDate = o.optString("start_date").trim(),
                endDate = o.optString("end_date").trim(),
                organizerTwitter = o.optString("organizer_twitter").trim(),
                materialsInfo = o.optString("materials_info").trim(),
            )
        }
    }

    suspend fun setInWatchLater(videoId: String, add: Boolean) = setInPlaylist("WL", videoId, add)

    suspend fun setInPlaylist(playlistId: String, videoId: String, @Suppress("UNUSED_PARAMETER") add: Boolean) = withContext(Dispatchers.IO) {
        if (!Account.signedIn) throw NotSignedInException()
        if (playlistId == "WL" || playlistId == "LL") {
            rateVideo(videoId, "idea")
            return@withContext
        }
        val form = FormBody.Builder()
            .add("playlist_id", playlistId)
            .add("video_id", videoId)
            .build()
        val req = Request.Builder()
            .url("${TekuZone.BASE_URL}/api/playlists/toggle-video")
            .post(form)
            .build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw YouTubeRefusedException(r.code)
        }
    }

    suspend fun saveChoices(videoId: String): List<PlaylistChoice> = withContext(Dispatchers.IO) {
        if (!Account.signedIn) throw NotSignedInException()
        val arr = getJsonArray("/api/playlists/my?video_id=${URLEncoder.encode(videoId, "UTF-8")}", requireAuth = true)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            PlaylistChoice(
                id = id,
                title = o.optString("name").ifBlank { "再生リスト" },
                contains = o.optBoolean("contains_current_video", false),
                privacy = "PUBLIC",
            )
        }
    }

    suspend fun createPlaylist(title: String, @Suppress("UNUSED_PARAMETER") privacy: String, videoId: String): String = withContext(Dispatchers.IO) {
        if (!Account.signedIn) throw NotSignedInException()
        val form = FormBody.Builder()
            .add("name", title)
            .add("video_id", videoId)
            .build()
        val req = Request.Builder()
            .url("${TekuZone.BASE_URL}/api/playlists/create")
            .post(form)
            .build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw YouTubeRefusedException(r.code)
            val json = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
            json.optJSONObject("new_playlist")?.optString("id")?.ifBlank { null }
                ?: throw IOException("再生リストの作成に失敗しました")
        }
    }

    class WatchSession(val videoId: String, val watchtimeUrl: String = "", val cpn: String = "")

    fun startWatch(videoId: String): WatchSession? {
        runCatching {
            // Calling GET /video/{id} records watch_history on the server when signed in.
            val req = Request.Builder()
                .url("${TekuZone.BASE_URL}/video/${URLEncoder.encode(videoId, "UTF-8")}")
                .get()
                .build()
            Net.client.newCall(req).execute().close()
        }
        runCatching {
            val payload = JSONObject()
                .put("video_id", videoId)
                .put("event_type", "view_start")
                .put("watch_time", 0)
                .put("progress", 0)
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${TekuZone.BASE_URL}/api/analytics/track")
                .post(body)
                .build()
            Net.client.newCall(req).execute().close()
        }
        return WatchSession(videoId)
    }

    fun pingWatchtime(
        session: WatchSession,
        positionSec: Double,
        @Suppress("UNUSED_PARAMETER") starts: List<Double>,
        @Suppress("UNUSED_PARAMETER") ends: List<Double>,
        @Suppress("UNUSED_PARAMETER") playing: Boolean,
        elapsedSec: Double,
        final: Boolean,
    ) {
        if (elapsedSec <= 0.5 && !final) return
        runCatching {
            val payload = JSONObject()
                .put("video_id", session.videoId)
                .put("event_type", if (final) "view_end" else "view_progress")
                .put("watch_time", elapsedSec.toInt().coerceAtLeast(1))
                .put("progress", positionSec.toInt().coerceAtLeast(0))
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${TekuZone.BASE_URL}/api/analytics/track")
                .post(body)
                .build()
            Net.client.newCall(req).execute().close()
        }
    }

    internal fun sendFeedback(@Suppress("UNUSED_PARAMETER") token: String) {}

    internal fun parseViews(text: String?): Long = text?.filter { it.isDigit() }?.toLongOrNull() ?: -1L
}

// ── JSON helpers kept for compatibility ────────────────────────────────────────

internal fun JSONObject.walk(visit: (key: String, obj: JSONObject) -> Unit) {
    val keys = keys()
    while (keys.hasNext()) {
        val k = keys.next()
        when (val v = opt(k)) {
            is JSONObject -> { visit(k, v); v.walk(visit) }
            is JSONArray -> v.walkArray(visit)
        }
    }
}

private fun JSONArray.walkArray(visit: (key: String, obj: JSONObject) -> Unit) {
    for (i in 0 until length()) {
        when (val v = opt(i)) {
            is JSONObject -> v.walk(visit)
            is JSONArray -> v.walkArray(visit)
        }
    }
}

internal fun JSONObject.forEachObject(visit: (JSONObject) -> Unit) {
    visit(this)
    val keys = keys()
    while (keys.hasNext()) {
        when (val v = opt(keys.next())) {
            is JSONObject -> v.forEachObject(visit)
            is JSONArray -> v.forEachObjectInArray(visit)
        }
    }
}

private fun JSONArray.forEachObjectInArray(visit: (JSONObject) -> Unit) {
    for (i in 0 until length()) {
        when (val v = opt(i)) {
            is JSONObject -> v.forEachObject(visit)
            is JSONArray -> v.forEachObjectInArray(visit)
        }
    }
}

internal fun JSONObject.findObject(key: String): JSONObject? {
    var found: JSONObject? = null
    walk { k, o -> if (found == null && k == key) found = o }
    return found
}

internal fun JSONObject.text(key: String): String? {
    val o = optJSONObject(key) ?: return optString(key).ifBlank { null }
    o.optString("simpleText").ifBlank { null }?.let { return it }
    o.optString("content").ifBlank { null }?.let { return it }
    val runs = o.optJSONArray("runs") ?: return null
    return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }.ifBlank { null }
}

internal fun JSONObject.thumbnails(): List<String>? {
    val arr = optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: optJSONArray("thumbnails") ?: return null
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("url")?.ifBlank { null } }
}

internal fun JSONObject.collectStrings(field: String): List<String> {
    val out = mutableListOf<String>()
    fun visitObj(o: JSONObject) {
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = o.opt(k)) {
                is String -> if (k == field && v.isNotBlank()) out += v
                is JSONObject -> visitObj(v)
                is JSONArray -> for (i in 0 until v.length()) (v.opt(i) as? JSONObject)?.let(::visitObj)
            }
        }
    }
    visitObj(this)
    return out
}
