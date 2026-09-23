package io.github.superthom196.maa.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <token>` to requests for our server, on either of its addresses.
 * The server hands out some artwork as plain URLs on other hosts (fanart.tv, TheAudioDB), and
 * those must never see a token that controls the whole library. A header already on the request
 * wins: the sign-in flow sends a fresh session token to a server not saved yet.
 */
class AuthInterceptor(private val config: ConfigStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val cfg = config.server.value
        if (cfg == null || cfg.token.isBlank() || req.header("Authorization") != null || !isOurs(req.url, cfg.urls)) {
            return chain.proceed(req)
        }
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer ${cfg.token}").build())
    }

    companion object {
        /** Whether [url] is on one of [homes]: same host, same effective port (80/443 when implicit). */
        fun isOurs(url: HttpUrl, homes: List<String>): Boolean = homes.any { home ->
            val h = home.toHttpUrlOrNull() ?: return@any false
            url.host == h.host && url.port == h.port
        }
    }
}
