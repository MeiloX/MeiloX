package com.ljyh.mei.source.script

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** MusicFree needs its original media object on subsequent playback, including queue restore. */
internal class CompatibilityTrackStore(private val directory: File) {
    data class Stored(val trackId: String, val track: JsonObject, val media: JsonObject)

    fun get(trackId: String): Stored? = synchronized(lock) {
        val file = File(directory, fileName(trackId))
        if (file.length() !in 1L..MAX_RECORD_BYTES.toLong()) return@synchronized null
        try {
            val data = JsonParser.parseString(file.readText()).asJsonObject
            val track = data.getAsJsonObject("track")
            if (data.get("trackId")?.asString != trackId || track.get("trackId")?.asString != trackId) {
                return@synchronized null
            }
            Stored(trackId, track, data.getAsJsonObject("media")).also { file.setLastModified(System.currentTimeMillis()) }
        } catch (_: Exception) {
            // An incomplete or obsolete individual record does not affect other tracks.
            null
        }
    }

    fun put(tracks: List<Stored>, ensureCurrent: () -> Unit) = synchronized(lock) {
        if (tracks.isEmpty()) return@synchronized
        if (tracks.size > MAX_TRACKS) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        val records = tracks.associate { record ->
            val name = fileName(record.trackId)
            val bytes = JsonObject().apply {
                addProperty("trackId", record.trackId)
                add("track", record.track)
                add("media", record.media)
            }.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_RECORD_BYTES || record.track.get("trackId")?.asString != record.trackId) {
                throw SourceException(SourceErrorCode.INVALID_RESPONSE)
            }
            name to bytes
        }
        if (records.values.sumOf { it.size.toLong() } > MAX_TOTAL_BYTES) {
            throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        }
        ensureCurrent()
        Files.createDirectories(directory.toPath())
        for ((name, bytes) in records) {
            ensureCurrent()
            val temporary = Files.createTempFile(directory.toPath(), ".track-", ".tmp")
            try {
                Files.write(temporary, bytes)
                ensureCurrent()
                Files.move(temporary, File(directory, name).toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        prune(records.keys)
    }

    private fun prune(currentRecords: Set<String>) {
        val files = directory.listFiles().orEmpty()
        files.filter { it.name.startsWith(".track-") && it.name.endsWith(".tmp") }.forEach(File::delete)
        val records = files.filter { it.isFile && RECORD_NAME.matches(it.name) }
            .sortedBy { it.lastModified() }
        var total = records.sumOf { it.length() }
        var count = records.size
        for (file in records) {
            if (count <= MAX_TRACKS && total <= MAX_TOTAL_BYTES) break
            if (file.name in currentRecords) continue
            val bytes = file.length()
            if (!file.delete()) continue
            total -= bytes
            count--
        }
    }

    private fun fileName(trackId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(trackId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".json"

    private companion object {
        val lock = Any()
        val RECORD_NAME = Regex("[a-f0-9]{64}\\.json")
        const val MAX_RECORD_BYTES = 32 * 1024
        const val MAX_TRACKS = 2048
        const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    }
}
