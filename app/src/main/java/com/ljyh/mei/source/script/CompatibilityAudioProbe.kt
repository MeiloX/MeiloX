package com.ljyh.mei.source.script

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import com.ljyh.mei.source.api.SourceException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString

internal const val LX_AUDIO_USER_AGENT = "Mozilla/5.0"

internal data class CompatibilityAudioInfo(val durationMs: Long, val format: Format, val length: Long?, val contentId: String) {
    val quality: String get() = when (format.sampleMimeType) {
        "audio/flac", "audio/alac" -> if (format.sampleRate > 48_000) "hires" else "lossless"
        else -> if (format.bitrate >= 256_000) "exhigh" else "standard"
    }

    fun matches(expectedMs: Long): Boolean =
        expectedMs > 0 && abs(durationMs - expectedMs) <= minOf(3_000, maxOf(150, expectedMs / 50))
}

/** Validates the actual recording length and audio format with limited range requests. */
internal suspend fun probeCompatibilityAudio(
    url: String, policy: ScriptNetworkPolicy, ensureCurrent: () -> Unit,
): CompatibilityAudioInfo? = runInterruptible(Dispatchers.IO) {
    val client = policy.audioClient.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    val reader = AudioProbeReader(url, client, ensureCurrent)
    val extractors = DefaultExtractorsFactory()
        .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING).createExtractors()
    try {
        val extractor = extractors.firstOrNull { candidate ->
            reader.position = 0
            try { candidate.sniff(DefaultExtractorInput(reader, 0, reader.length)) }
            catch (_: java.io.EOFException) { false }
        } ?: return@runInterruptible null
        reader.position = 0
        var input = DefaultExtractorInput(reader, 0, reader.length)
        var durationUs = C.TIME_UNSET
        var audio: Format? = null
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
                override fun format(format: Format) { if (type == C.TRACK_TYPE_AUDIO) audio = format }
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { durationUs = seekMap.durationUs }
        })
        fun audioInfo(): CompatibilityAudioInfo? {
            val format = audio ?: return null
            if (durationUs <= 0) return null
            return reader.audioInfo(durationUs / 1_000, format)
        }
        val seek = PositionHolder()
        repeat(512) {
            ensureCurrent()
            audioInfo()?.let { return@runInterruptible it }
            when (extractor.read(input, seek)) {
                Extractor.RESULT_END_OF_INPUT -> return@runInterruptible audioInfo()
                Extractor.RESULT_SEEK -> {
                    reader.position = seek.position
                    input = DefaultExtractorInput(reader, seek.position, reader.length)
                }
            }
        }
        null
    } catch (error: SourceException) { throw error }
    catch (_: IOException) { null }
    finally { extractors.forEach(Extractor::release) }
}

private class AudioProbeReader(
    private val url: String, private val client: OkHttpClient, private val ensureCurrent: () -> Unit,
) : DataReader {
    var position = 0L
    var length = C.LENGTH_UNSET.toLong()
        private set
    private var start = -1L
    private var chunk = ByteArray(0)
    private var prefix: ByteArray? = null
    private var bytesLeft = 1024 * 1024
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)

    fun audioInfo(durationMs: Long, format: Format): CompatibilityAudioInfo {
        // Signed URLs, mirror hosts and LX quality labels can all point to the same encoded stream.
        val digest = checkNotNull(prefix).toByteString().sha256().hex()
        val id = "lx:${format.sampleMimeType}:${format.sampleRate}:${format.channelCount}:$digest"
        return CompatibilityAudioInfo(durationMs, format, length.takeIf { it > 0 }, id)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        ensureCurrent()
        if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline || position < 0) throw IOException()
        if (length == 0) return 0
        if (this.length >= 0 && position >= this.length) return C.RESULT_END_OF_INPUT
        if (position < start || position >= start + chunk.size) loadChunk()
        if (chunk.isEmpty()) return C.RESULT_END_OF_INPUT
        val index = (position - start).toInt()
        val count = minOf(length, chunk.size - index)
        chunk.copyInto(buffer, offset, index, index + count)
        position += count
        return count
    }

    private fun loadChunk() {
        if (bytesLeft <= 0 || position > Long.MAX_VALUE - 128 * 1024) throw IOException()
        val size = minOf(bytesLeft, 128 * 1024)
        val request = Request.Builder().url(url).deferDomainNotice()
            .header("User-Agent", LX_AUDIO_USER_AGENT)
            .header("Range", "bytes=$position-${position + size - 1}")
            .header("Accept-Encoding", "identity").build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                206 -> {
                    val range = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty()) ?: throw IOException()
                    if (range.groupValues[1].toLongOrNull() != position) throw IOException()
                    length = range.groupValues[2].toLongOrNull() ?: length
                }
                200 -> if (position == 0L) length = response.body.contentLength() else throw IOException()
                else -> throw IOException()
            }
            start = position
            val source = response.body.source()
            source.request(size.toLong())
            chunk = source.readByteArray(minOf(source.buffer.size, size.toLong()))
            if (start == 0L && prefix == null) prefix = chunk
            bytesLeft -= chunk.size
        }
    }

    companion object {
        private val CONTENT_RANGE = Regex("""bytes (\d+)-\d+/(\d+|\*)""")
    }
}
