package com.ljyh.mei.source.update

import android.content.Context
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.script.ScriptNetworkPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Request

data class CompatibilityMarketEntry(
    val id: String,
    val name: String,
    val version: String,
    val format: String,
    val repository: String,
    val revision: String,
    val path: String,
    val sha256: String,
) {
    val packageId: String
        get() {
            val identity = listOf(format, id, repository.lowercase(Locale.ROOT), path).joinToString("\u0000")
            return "compat.$format.${sha256Hex(identity.toByteArray(Charsets.UTF_8)).take(40)}"
        }

    val url: String
        get() {
            val (owner, project) = SourceMarketIndex.repositoryParts(repository)
            return "https://raw.githubusercontent.com/$owner/$project/$revision/$path"
        }
}

/** Compatibility entries use a separate index so earlier native-market clients can still read theirs. */
@Singleton
class CompatibilityMarket @Inject constructor(@ApplicationContext private val context: Context) {
    private val policy = ScriptNetworkPolicy(marketHosts)
    private val client = policy.marketClient()

    fun bundled(): List<CompatibilityMarketEntry> =
        context.assets.open("source-market/compatibility.json").bufferedReader().use {
            CompatibilityMarketIndex.parse(it.readText())
        }

    suspend fun refresh(): List<CompatibilityMarketEntry>? {
        val request = Request.Builder().url(CompatibilityMarketIndex.URL).build()
        return policy.readMarketFile(client, request) { response, ensureCurrent ->
            if (response.code == 404) return@readMarketFile null
            if (!response.isSuccessful) throw SourceException(SourceErrorCode.NETWORK)
            val body = response.body
            if (body.contentLength() > INDEX_BYTES) malformed()
            val source = body.source()
            if (source.request(INDEX_BYTES + 1L)) malformed()
            val bytes = source.readByteArray()
            ensureCurrent()
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            CompatibilityMarketIndex.parse(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        }
    }

    suspend fun download(entry: CompatibilityMarketEntry, destination: File) {
        CompatibilityMarketIndex.validate(entry)
        val request = Request.Builder().url(entry.url).build()
        try {
            policy.readMarketFile(client, request) { response, ensureCurrent ->
                if (!response.isSuccessful) throw SourceException(SourceErrorCode.NETWORK)
                val body = response.body
                if (body.contentLength() > SCRIPT_BYTES) malformed()
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            ensureCurrent()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > SCRIPT_BYTES) malformed()
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                ensureCurrent()
                if (total == 0 || digest.digest().hex() != entry.sha256) {
                    throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Compatibility source digest mismatch")
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
}

internal object CompatibilityMarketIndex {
    const val URL = "https://raw.githubusercontent.com/MeiloX/MeiloX-Source-Registry/main/compatibility.json"
    private val fields = setOf("id", "name", "version", "format", "repository", "revision", "path", "sha256")
    private val idPattern = Regex("[a-z][a-z0-9._-]{2,79}")
    private val scriptPath = Regex("[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*\\.js")
    private val revisionPattern = Regex("[a-f0-9]{40}")
    private val digestPattern = Regex("[a-f0-9]{64}")

    fun parse(json: String): List<CompatibilityMarketEntry> {
        try {
            require(json.toByteArray(Charsets.UTF_8).size <= INDEX_BYTES)
            val reader = JsonReader(StringReader(json))
            val seen = mutableSetOf<String>()
            var entries: List<CompatibilityMarketEntry>? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName()
                require(seen.add(field))
                when (field) {
                    "schemaVersion" -> require(reader.peek() == JsonToken.NUMBER && reader.nextString() == "1")
                    "scripts" -> {
                        val values = mutableListOf<CompatibilityMarketEntry>()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(values.size < 100)
                            values += readEntry(reader)
                        }
                        reader.endArray()
                        entries = values
                    }
                    else -> malformed()
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && seen == setOf("schemaVersion", "scripts"))
            return requireNotNull(entries).also { values ->
                require(values.map(CompatibilityMarketEntry::id).distinct().size == values.size)
                require(values.map { it.repository.lowercase(Locale.ROOT) to it.path }.distinct().size == values.size)
            }
        } catch (_: Exception) {
            malformed()
        }
    }

    private fun readEntry(reader: JsonReader): CompatibilityMarketEntry {
        val values = mutableMapOf<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            require(key in fields && key !in values && reader.peek() == JsonToken.STRING)
            values[key] = reader.nextString().also { require(it.length <= 512) }
        }
        reader.endObject()
        require(values.keys == fields)
        return CompatibilityMarketEntry(values.getValue("id"), values.getValue("name"), values.getValue("version"),
            values.getValue("format"), values.getValue("repository"), values.getValue("revision"), values.getValue("path"),
            values.getValue("sha256")).also(::validate)
    }

    fun validate(entry: CompatibilityMarketEntry) {
        require(entry.format in setOf("lx", "musicfree") && idPattern.matches(entry.id) &&
            entry.id.startsWith("${entry.format}."))
        require(entry.name.isNotBlank() && entry.name.length <= 100 && entry.name.none(Char::isISOControl))
        require(SourceVersion.parse(entry.version) != null)
        require(entry.repository.length <= 250)
        SourceMarketIndex.repositoryParts(entry.repository)
        require(entry.path.length <= 512 && scriptPath.matches(entry.path))
        require(revisionPattern.matches(entry.revision) && entry.revision.any { it != '0' })
        require(digestPattern.matches(entry.sha256) && entry.sha256.any { it != '0' })
    }
}

private const val INDEX_BYTES = 256 * 1024
private const val SCRIPT_BYTES = 1024 * 1024

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
private fun malformed(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Invalid compatibility source index")
