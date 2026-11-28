package com.ljyh.mei.source.update

import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.script.ScriptNetworkPolicy
import java.net.ProxySelector
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal val marketHosts = listOf("raw.githubusercontent.com", "api.github.com")

internal fun ScriptNetworkPolicy.marketClient(): OkHttpClient = client.newBuilder()
    .proxy(null)
    .apply { ProxySelector.getDefault()?.let { proxySelector(it) } }
    .dns { host ->
        Dns.SYSTEM.lookup(host).also { if (host in marketHosts) ScriptNetworkPolicy.validateResolvedAddresses(it) }
    }
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val location = response.header("Location")
        val target = if (response.code == 301 && location != null) request.url.resolve(location) else null
        if (target != null && isGitHubRepositoryRedirect(request.url, target)) {
            response.close()
            chain.proceed(request.newBuilder().url(target).build())
        } else response
    }
    .build()

/** A transferred repository's REST endpoint redirects from owner/name to its numeric repository ID. */
internal fun isGitHubRepositoryRedirect(from: HttpUrl, to: HttpUrl): Boolean {
    val before = from.pathSegments
    val after = to.pathSegments
    return from.host == "api.github.com" && to.host == from.host && to.isHttps && to.port == 443 &&
        to.username.isEmpty() && to.password.isEmpty() && to.fragment == null &&
        before.size >= 4 && before[0] == "repos" && after.size == before.size - 1 &&
        after[0] == "repositories" && after[1].matches(Regex("[1-9][0-9]*")) &&
        before.drop(3) == after.drop(2) && from.encodedQuery == to.encodedQuery
}

/** Both GitHub endpoints return the same file at the requested ref. Callers own size and integrity checks. */
internal suspend fun <T> ScriptNetworkPolicy.readMarketFile(
    client: OkHttpClient,
    request: Request,
    consume: (Response, () -> Unit) -> T,
): T {
    val raw = request.url
    require(raw.host == "raw.githubusercontent.com" && raw.isHttps && raw.port == 443 && raw.pathSegments.size >= 4)
    try {
        return exchange(client.newCall(request), {}) { response, guard ->
            if (response.code == 404) throw SourceException(SourceErrorCode.NOT_FOUND)
            if (response.code in setOf(403, 408, 429) || response.code in 500..599) {
                throw SourceException(SourceErrorCode.NETWORK)
            }
            consume(response, guard)
        }
    } catch (error: SourceException) {
        if (error.code !in setOf(SourceErrorCode.NETWORK, SourceErrorCode.TIMEOUT, SourceErrorCode.NOT_FOUND)) throw error
    }
    val parts = raw.pathSegments
    val url = "https://api.github.com".toHttpUrl().newBuilder()
        .addPathSegment("repos").addPathSegment(parts[0]).addPathSegment(parts[1]).addPathSegment("contents")
        .apply { parts.drop(3).forEach(::addPathSegment) }
        .addQueryParameter("ref", parts[2]).build()
    val api = request.newBuilder().url(url).header("Accept", "application/vnd.github.raw+json").build()
    return exchange(client.newCall(api), {}, consume)
}
