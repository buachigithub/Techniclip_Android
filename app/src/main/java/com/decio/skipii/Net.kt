package com.decio.skipii

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object TekuZone {
    const val BASE_URL = "https://zone.techniclip.com"
    const val HOST = "zone.techniclip.com"

    fun absUrl(path: String?): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            p.startsWith("http://") || p.startsWith("https://") -> p
            p.startsWith("//") -> "https:$p"
            p.startsWith("/") -> "$BASE_URL$p"
            else -> "$BASE_URL/$p"
        }
    }

    fun usernameFromUrl(urlOrId: String?): String? {
        val raw = urlOrId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            "/user/" in raw -> raw.substringAfter("/user/").substringBefore('/').substringBefore('?')
            "/channel/" in raw -> raw.substringAfter("/channel/").substringBefore('/').substringBefore('?')
            raw.startsWith("@") -> raw.removePrefix("@")
            raw.startsWith("http://") || raw.startsWith("https://") -> raw.trimEnd('/').substringAfterLast('/')
            else -> raw
        }.trim().takeIf { it.isNotEmpty() }
    }
}

/** One HTTP client for the whole app: API requests, Coil images and media playback share it. */
object Net {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36 TekuZoneApp/1.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .addInterceptor { chain ->
                val req = chain.request()
                val builder = req.newBuilder()
                if (req.header("User-Agent") == null) {
                    builder.header("User-Agent", USER_AGENT)
                }
                if (req.url.host.endsWith("techniclip.com") && req.header("Cookie") == null) {
                    val cookie = Account.cookieHeader()
                    if (cookie.isNotBlank()) {
                        builder.header("Cookie", cookie)
                    }
                }
                val response = chain.proceed(builder.build())
                if (req.url.host.endsWith("techniclip.com")) {
                    Account.absorbSetCookies(response.headers("Set-Cookie"))
                }
                response
            }
            .build()
    }

    @Volatile private var mediaHost: String? = TekuZone.HOST

    fun noteMediaHost(url: String?) {
        url?.toHttpUrlOrNull()?.host?.let { mediaHost = it }
    }

    fun prewarmMediaConnection() {
        val host = mediaHost ?: TekuZone.HOST
        val request = okhttp3.Request.Builder()
            .url("https://$host/api/video-streaming/config")
            .head()
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
        })
    }
}
