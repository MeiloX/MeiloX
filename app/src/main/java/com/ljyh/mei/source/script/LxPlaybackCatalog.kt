package com.ljyh.mei.source.script

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.SourceAlbum
import com.ljyh.mei.source.api.SourceArtist
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.core.TrackMatcher
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Only platform identifiers from the same recording may be supplied to an LX script. */
internal object LxPlaybackCatalog {
    val platforms = setOf("wy", "tx", "kw", "kg", "mg")
    private val tiers = listOf("128k", "320k", "flac", "flac24bit")
    private val transport by lazy {
        ScriptNetworkPolicy(listOf("interface.music.163.com", "c.y.qq.com", "u.y.qq.com", "search.kuwo.cn",
            "songsearch.kugou.com", "c.musicapp.migu.cn"), strictRedirects = true)
    }
    private val matcher = TrackMatcher()
    private val demoVersion = Regex("""(?i)(?<![a-z])demo(?![a-z])|小样|小樣""")
    private data class Entry(val expiresAt: Long, val candidates: List<JsonObject>)
    private val cache = LinkedHashMap<Pair<Track, String>, Entry>(64, 0.75f, true)
    private val locks = Array(16) { Mutex() }

    suspend fun candidates(track: Track, platform: String): List<JsonObject> {
        if (platform !in platforms) return emptyList()
        if (platform == "wy") {
            if (track.ref.providerId == ProviderIds.NETEASE) {
                if (track.ref.trackId.toLongOrNull()?.let { it > 0 } != true) return emptyList()
                return listOf(metadata(track, platform, track.ref.trackId))
            }
            if (track.ref.providerId != "qqmusic") return emptyList()
        }
        val key = track to platform
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val cached = synchronized(cache) { cache[key] }
            if (cached != null && System.nanoTime() < cached.expiresAt) return@withLock cached.candidates
            val rows = withTimeoutOrNull(5_000L) { lookup(track, platform) }
                ?: throw SourceException(SourceErrorCode.TIMEOUT)
            synchronized(cache) {
                val lifetime = TimeUnit.SECONDS.toNanos(if (rows.isEmpty()) 30 else 300)
                cache[key] = Entry(System.nanoTime() + lifetime, rows)
                if (cache.size > 64) cache.remove(cache.entries.first().key)
            }
            rows
        }
    }

    private suspend fun lookup(track: Track, platform: String): List<JsonObject> {
        if (platform == "tx" && track.ref.providerId == "qqmusic") {
            val id = track.ref.trackId
            if (!id.matches(Regex("[A-Za-z0-9]{1,80}"))) return emptyList()
            val node = JsonObject().apply {
                addProperty("module", "music.pf_song_detail_svr")
                addProperty("method", "get_song_detail_yqq")
                add("param", JsonObject().apply { addProperty("song_mid", id) })
            }
            val data = fetch("https://u.y.qq.com/cgi-bin/musicu.fcg", "data" to JsonObject().apply { add("req", node) }.toString())
            val song = data.obj("req").obj("data").obj("track_info")
            if (song.str("mid") != id || song.str("id").isBlank()) return emptyList()
            return listOf(metadata(track, platform, song.str("id")).apply {
                addProperty("songmid", id)
                addProperty("strMediaMid", song.obj("file").str("media_mid"))
                addProperty("albumId", song.obj("album").str("mid"))
                addProperty("albumMid", song.obj("album").str("mid"))
            })
        }
        val query = (listOf(track.title) + track.artists.take(2).map { it.name }).joinToString(" ").take(256)
        val rows = when (platform) {
            "wy" -> fetch("https://interface.music.163.com/api/search/get/", "s" to query, "type" to "1",
                "offset" to "0", "limit" to "5").obj("result").rows("songs")
            "tx" -> fetch("https://c.y.qq.com/soso/fcgi-bin/client_search_cp", "w" to query, "format" to "json",
                "p" to "1", "n" to "5").obj("data").obj("song").rows("list")
            "kw" -> fetch("https://search.kuwo.cn/r.s", "all" to query, "stype" to "comprehensive", "correct" to "1",
                "vipver" to "1", "encoding" to "utf8", "rformat" to "json", "mobi" to "1",
                "show_copyright_off" to "1", "searchapi" to "6").rows("content")
                .flatMap { it.obj("musicpage").rows("abslist") }
            "kg" -> fetch("https://songsearch.kugou.com/song_search_v2", "keyword" to query, "page" to "1",
                "pagesize" to "5", "platform" to "WebFilter", "filter" to "2", "privilege_filter" to "0")
                .obj("data").rows("lists")
            "mg" -> {
                val matches = fetch("https://c.musicapp.migu.cn/v1.0/content/search_all.do", "text" to query,
                    "pageNo" to "1", "pageSize" to "5", "isCopyright" to "1", "sort" to "1", "searchSwitch" to "{\"song\":1}")
                    .obj("songResultData").rows("result").take(5)
                // Migu search omits duration. Read the corresponding catalog record before matching.
                matches.filter { it.str("name").trim().equals(track.title.trim(), ignoreCase = true) }.mapNotNull { row ->
                    val copyright = row.str("copyrightId").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    fetch("https://c.musicapp.migu.cn/MIGUM3.0/v1.0/content/resourceinfo.do", "copyrightId" to copyright,
                        "resourceType" to "2").rows("resource").firstOrNull {
                        it.str("copyrightId") == copyright && it.str("contentId") == row.str("contentId")
                    }?.let { details -> row.apply {
                        addProperty("length", details.str("length"))
                        addProperty("songId", details.str("songId"))
                    } }
                }
            }
            else -> emptyList()
        }
        return listOfNotNull(match(track, platform, rows.take(5)))
    }

    internal fun match(origin: Track, platform: String, rows: List<JsonObject>): JsonObject? {
        val candidates = rows.mapNotNull { row ->
            val id: String
            val title: String
            val artists: List<String>
            val album: String
            val durationMs: Long
            var version: String? = null
            when (platform) {
                "wy" -> {
                    id = row.str("id")
                    if (id.toLongOrNull()?.let { it > 0 } != true) return@mapNotNull null
                    title = row.str("name")
                    artists = row.rows("artists").map { it.str("name") }
                    album = row.obj("album").str("name")
                    durationMs = row.str("duration").toLongOrNull() ?: 0
                    version = row.get("alias")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                        ?.filter(JsonElement::isJsonPrimitive)?.joinToString(" ") { it.asString }
                    // The catalog lists some demos under the studio title, even with the same duration.
                    if (demoVersion.containsMatchIn("$title $album ${version.orEmpty()}") !=
                        demoVersion.containsMatchIn("${origin.title} ${origin.album.title} ${origin.version.orEmpty()}")) return@mapNotNull null
                }
                "tx" -> {
                    id = row.str("songid")
                    if (row.str("songmid").isBlank()) return@mapNotNull null
                    title = row.str("songname")
                    artists = row.rows("singer").map { it.str("name") }
                    album = row.str("albumname")
                    durationMs = row.secondsAsMillis("interval")
                }
                "kw" -> {
                    id = row.str("MUSICRID").substringAfterLast('_')
                    title = row.str("SONGNAME")
                    artists = row.str("ARTIST").split('&')
                    album = row.str("ALBUM")
                    durationMs = row.secondsAsMillis("DURATION")
                }
                "kg" -> {
                    id = row.str("Audioid")
                    if (row.str("FileHash").isBlank()) return@mapNotNull null
                    title = listOf(row.str("OriSongName"), row.str("Suffix")).filter(String::isNotBlank).joinToString(" ")
                    artists = row.rows("Singers").map { it.str("name") }.ifEmpty { row.str("SingerName").split('、') }
                    album = row.str("AlbumName")
                    durationMs = row.secondsAsMillis("Duration")
                }
                "mg" -> {
                    id = row.str("contentId")
                    if (row.str("copyrightId").isBlank()) return@mapNotNull null
                    title = row.str("name")
                    artists = row.rows("singers").map { it.str("name") }
                    album = row.rows("albums").firstOrNull()?.str("name").orEmpty()
                    val parts = row.str("length").split(':')
                    if (parts.size !in 2..3 || parts.any { it.toIntOrNull() !in 0..59 }) return@mapNotNull null
                    durationMs = TimeUnit.SECONDS.toMillis(parts.fold(0L) { total, part -> total * 60 + part.toInt() })
                }
                else -> return@mapNotNull null
            }
            if (id.isBlank() || title.isBlank() || durationMs !in 1..86_400_000) return@mapNotNull null
            val candidate = Track(TrackRef(platform, id), title, artists.map(String::trim).filter(String::isNotBlank).map(::SourceArtist),
                SourceAlbum(album), durationMs = durationMs, version = version)
            candidate to row
        }
        val selected = matcher.automatic(matcher.rank(origin, candidates.map { it.first }))?.track ?: return null
        val row = candidates.first { it.first.ref == selected.ref }.second
        return metadata(selected, platform, selected.ref.trackId).apply {
            when (platform) {
                "wy" -> addProperty("albumId", row.obj("album").str("id"))
                "tx" -> {
                    addProperty("songmid", row.str("songmid"))
                    addProperty("strMediaMid", row.str("strMediaMid").ifBlank { row.str("media_mid") })
                    addProperty("albumId", row.str("albummid"))
                    addProperty("albumMid", row.str("albummid"))
                }
                "kw" -> addProperty("albumId", row.str("ALBUMID"))
                "kg" -> {
                    addProperty("hash", row.str("FileHash"))
                    addProperty("albumId", row.str("AlbumID"))
                    val hashes = tiers.zip(listOf("FileHash", "HQFileHash", "SQFileHash", "ResFileHash"))
                        .mapNotNull { (tier, field) -> row.str(field).takeIf { hash -> hash.isNotBlank() && hash.any { it != '0' } }?.let { tier to it } }
                    setTypes(hashes.toMap())
                }
                "mg" -> {
                    addProperty("copyrightId", row.str("copyrightId"))
                    addProperty("contentId", row.str("contentId"))
                    val song = row.str("songId").ifBlank { row.str("id") }
                    if (song.isBlank()) return null
                    addProperty("songmid", song)
                    add("songId", exactId(song))
                    addProperty("albumId", row.rows("albums").firstOrNull()?.str("id").orEmpty())
                }
            }
        }
    }

    private fun metadata(track: Track, platform: String, id: String) = JsonObject().apply {
        addProperty("source", platform)
        addProperty("name", track.title)
        addProperty("singer", track.artists.joinToString("、") { it.name })
        addProperty("albumName", track.album.title)
        addProperty("albumId", track.album.ref?.albumId.orEmpty())
        addProperty("songmid", id)
        add("songId", exactId(id))
        val seconds = track.durationMs / 1_000
        addProperty("interval", String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60))
        addProperty("_interval", seconds)
        addProperty("img", track.artworkUrl.takeIf { it.startsWith("https://") }.orEmpty())
        setTypes(tiers.associateWith { "" })
        add("typeUrl", JsonObject())
    }

    private fun exactId(id: String): JsonPrimitive {
        val number = id.toLongOrNull()?.takeIf { it in 1L..9_007_199_254_740_991L }
        return number?.let(::JsonPrimitive) ?: JsonPrimitive(id)
    }

    private fun JsonObject.setTypes(hashes: Map<String, String>) {
        val list = JsonArray()
        val indexed = JsonObject()
        hashes.forEach { (quality, hash) ->
            val fields = JsonObject().apply { if (hash.isNotEmpty()) addProperty("hash", hash) }
            indexed.add(quality, fields)
            list.add(fields.deepCopy().apply { addProperty("type", quality) })
        }
        add("types", list)
        add("_types", indexed)
    }

    private suspend fun fetch(endpoint: String, vararg params: Pair<String, String>): JsonObject {
        val url = endpoint.toHttpUrl().newBuilder().apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val response = transport.request(JsonObject().apply {
            addProperty("url", url.toString())
            add("headers", JsonObject().apply {
                addProperty("User-Agent", "Mozilla/5.0")
                if (url.host.endsWith(".qq.com")) addProperty("Referer", "https://y.qq.com/")
            })
        })
        if (response.get("status")?.asInt !in 200..299) throw SourceException(SourceErrorCode.NETWORK)
        val payload = try { JsonParser.parseString(response.get("body").asString) }
            catch (_: com.google.gson.JsonParseException) { throw SourceException(SourceErrorCode.INVALID_RESPONSE) }
        return payload.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)
    }

    private fun JsonObject.str(key: String) = get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
    private fun JsonObject.secondsAsMillis(key: String) = TimeUnit.SECONDS.toMillis(str(key).toLongOrNull() ?: 0)
    private fun JsonObject.obj(key: String) = get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: JsonObject()
    private fun JsonObject.rows(key: String) = get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
        ?.filter(JsonElement::isJsonObject)?.map { it.asJsonObject }.orEmpty()
}
