package com.ljyh.mei.source.platform

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.script.ScriptNetworkPolicy
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import timber.log.Timber

private const val QQ_MQTT_HOST = "mu.y.qq.com"
private const val QQ_MQTT_PATH = "ws/handshake"
private const val QR_TIMEOUT_MS = 120_000L
private const val HANDSHAKE_TIMEOUT_MS = 20_000L
private const val KEEP_ALIVE_SECONDS = 45
private const val MAX_PACKET_BYTES = 16 * 1024
private const val MAX_REDIRECTS = 3
private const val SUBSCRIPTION_ID = 1
private val qrcodeIdPattern = Regex("[A-Za-z0-9_.-]{1,256}")
private val brokerReferencePattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?:[0-9]{1,5}")
private val musicIdPattern = Regex("[1-9][0-9]{0,19}")
private val mqttPing = "c000".decodeHex()

private val qqMusicQrClient by lazy {
    ScriptNetworkPolicy(listOf(QQ_MQTT_HOST)).client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

/** Waits for approval of an existing QQ Music App login QR code. */
internal suspend fun authorizeQQMusicQr(qrcodeId: String, onReady: () -> Unit, onScanned: () -> Unit): JsonObject {
    if (!qrcodeIdPattern.matches(qrcodeId)) invalidQrMqtt()
    val clientId = "${System.currentTimeMillis()}${Random.nextInt(1000, 10000)}"
    val topic = "management.qrcode_login/$qrcodeId"
    val visited = mutableSetOf<String>()
    return withTimeoutOrNull(QR_TIMEOUT_MS) {
        var node: String? = null
        repeat(MAX_REDIRECTS + 1) {
            when (val outcome = authorizeOnBroker(clientId, qrcodeId, topic, node, onReady, onScanned)) {
                is QrMqttOutcome.Authorized -> return@withTimeoutOrNull outcome.result
                is QrMqttOutcome.Redirect -> {
                    node = outcome.node
                    if (!visited.add(outcome.node)) invalidQrMqtt()
                }
            }
        }
        throw SourceException(SourceErrorCode.UNAVAILABLE)
    } ?: throw SourceException(SourceErrorCode.TIMEOUT)
}

private sealed interface QrMqttOutcome {
    class Redirect(val node: String) : QrMqttOutcome
    class Authorized(val result: JsonObject) : QrMqttOutcome
}

// Broker properties and topics follow the QQMusicApi mobile QR protocol.
private suspend fun authorizeOnBroker(
    clientId: String,
    qrcodeId: String,
    topic: String,
    node: String?,
    onReady: () -> Unit,
    onScanned: () -> Unit,
): QrMqttOutcome {
    val url = HttpUrl.Builder().scheme("https").host(QQ_MQTT_HOST).addPathSegments(QQ_MQTT_PATH)
        .apply { node?.let(::addPathSegment) }.build()
    val request = Request.Builder().url(url)
        .header("Sec-WebSocket-Protocol", "mqtt")
        .header("Origin", "https://y.qq.com")
        .header("Referer", "https://y.qq.com/")
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
        .build()
    val messages = Channel<ByteString>(8)
    val socket = qqMusicQrClient.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!response.header("Sec-WebSocket-Protocol").equals("mqtt", ignoreCase = true)) {
                messages.close(SourceException(SourceErrorCode.INVALID_RESPONSE))
                webSocket.cancel()
            } else if (!webSocket.send(connectPacket(clientId, qrcodeId))) {
                messages.close(SourceException(SourceErrorCode.NETWORK))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size !in 1..MAX_PACKET_BYTES || messages.trySend(bytes).isFailure) {
                messages.close(SourceException(SourceErrorCode.INVALID_RESPONSE))
                webSocket.cancel()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.close(SourceException(SourceErrorCode.INVALID_RESPONSE))
            webSocket.cancel()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (messages.close(SourceException(SourceErrorCode.NETWORK))) {
                Timber.tag("QQMusicQrLogin").w("Authorization WebSocket closed: %d", code)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (messages.close(SourceException(SourceErrorCode.NETWORK))) {
                Timber.tag("QQMusicQrLogin").w("Authorization WebSocket failed: %s", t.javaClass.simpleName)
            }
        }
    })
    try {
        val incoming = MqttPacketStream(messages)
        val ack = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { incoming.read() } ?: throw SourceException(SourceErrorCode.TIMEOUT)
        if (ack.header != 0x20 || ack.body.u8() != 0) invalidQrMqtt()
        val code = ack.body.u8()
        val properties = ack.body.properties()
        if (!ack.body.exhausted) invalidQrMqtt()
        if (code == 0x9c || code == 0x9d) {
            val reference = properties.serverReference ?: invalidQrMqtt()
            // QQ returns a broker node as a path segment under the same TLS origin.
            if (!brokerReferencePattern.matches(reference) || reference.substringAfterLast(':').toInt() !in 1..65535) invalidQrMqtt()
            return QrMqttOutcome.Redirect(reference)
        }
        if (code != 0) throw SourceException(SourceErrorCode.UNAVAILABLE)
        if (!socket.send(subscribePacket(topic))) throw SourceException(SourceErrorCode.NETWORK)
        val subscription = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { incoming.read() }
            ?: throw SourceException(SourceErrorCode.TIMEOUT)
        if (subscription.header != 0x90 || subscription.body.u16() != SUBSCRIPTION_ID) invalidQrMqtt()
        subscription.body.properties()
        val granted = subscription.body.u8()
        if (!subscription.body.exhausted) invalidQrMqtt()
        if (granted != 0) throw SourceException(SourceErrorCode.UNAVAILABLE)
        onReady()
        return coroutineScope {
            val keepAlive = properties.keepAlive ?: KEEP_ALIVE_SECONDS
            val heartbeat = launch {
                if (keepAlive > 0) while (true) {
                    delay(minOf(keepAlive * 500L, 20_000L))
                    if (!socket.send(mqttPing)) {
                        messages.close(SourceException(SourceErrorCode.NETWORK))
                        break
                    }
                }
            }
            try {
                QrMqttOutcome.Authorized(awaitQrConfirmation(incoming, topic, qrcodeId, onScanned))
            } finally {
                heartbeat.cancel()
            }
        }
    } finally {
        messages.cancel()
        socket.cancel()
    }
}

private suspend fun awaitQrConfirmation(
    incoming: MqttPacketStream,
    topic: String,
    qrcodeId: String,
    onScanned: () -> Unit,
): JsonObject {
    var scanned = false
    while (true) {
        val packet = incoming.read()
        when (packet.header) {
            0xd0 -> if (!packet.body.exhausted) invalidQrMqtt()
            0xe0 -> {
                val reason = if (packet.body.exhausted) 0 else packet.body.u8()
                Timber.tag("QQMusicQrLogin").w("Authorization broker disconnected: %d", reason)
                throw SourceException(SourceErrorCode.NETWORK)
            }
            0x30, 0x31 -> {
                val publishedTopic = packet.body.utf8()
                val type = packet.body.properties().eventType
                if (publishedTopic != topic) {
                    Timber.tag("QQMusicQrLogin").w("Authorization topic did not match")
                    continue
                }
                // The server can retain a terminal event. This subscription is
                // unique to the fresh QR and the token is exchanged for that QR.
                when (type) {
                    "scanned" -> if (!scanned) {
                        scanned = true
                        onScanned()
                    }
                    "canceled" -> throw SourceException(SourceErrorCode.CANCELLED)
                    "timeout" -> throw SourceException(SourceErrorCode.TIMEOUT)
                    "loginFailed" -> throw SourceException(SourceErrorCode.UNAVAILABLE)
                    "cookies" -> {
                        readQrToken(packet.body, qrcodeId)?.let { return it }
                        Timber.tag("QQMusicQrLogin").w("Authorization event did not match the active QR")
                    }
                }
            }
            else -> invalidQrMqtt()
        }
    }
}

private fun readQrToken(payload: MqttFields, qrcodeId: String): JsonObject? {
    val json = try {
        JsonParser.parseString(payload.remainingText()).takeIf { it.isJsonObject }?.asJsonObject ?: invalidQrMqtt()
    } catch (_: JsonParseException) { invalidQrMqtt() }
    for (key in listOf("qrcodeId", "qrcodeID", "qrCodeId", "qrCodeID")) {
        val field = json.get(key) ?: continue
        if (!field.isJsonPrimitive || !field.asJsonPrimitive.isString || field.asString != qrcodeId) return null
    }
    val cookies = json.get("cookies")?.takeIf { it.isJsonObject }?.asJsonObject ?: invalidQrMqtt()
    fun value(name: String) = cookies.get(name)?.takeIf { it.isJsonObject }?.asJsonObject?.get("value")
        ?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: invalidQrMqtt()
    val musicId = value("qqmusic_uin").asString
    val token = value("qqmusic_key").takeIf { it.isString }?.asString ?: invalidQrMqtt()
    if (!musicIdPattern.matches(musicId) || token.length !in 1..4096 || token.any { it.isWhitespace() || it.isISOControl() }) invalidQrMqtt()
    return JsonObject().apply {
        addProperty("musicId", musicId)
        addProperty("token", token)
        addProperty("qrcodeId", qrcodeId)
    }
}

private fun connectPacket(clientId: String, qrcodeId: String): ByteString = mqttPacket(0x10) {
    mqttString("MQTT")
    writeByte(5)
    writeByte(2) // Clean Start.
    writeShort(KEEP_ALIVE_SECONDS)
    mqttProperties {
        writeByte(0x15)
        mqttString("pass")
        mqttUserProperty("tmeAppID", "qqmusic")
        mqttUserProperty("business", "management")
        mqttUserProperty("hashTag", qrcodeId)
        mqttUserProperty("clientTag", "management.user")
        mqttUserProperty("userID", qrcodeId)
    }
    mqttString(clientId)
}

private fun subscribePacket(topic: String): ByteString = mqttPacket(0x82) {
    writeShort(SUBSCRIPTION_ID)
    mqttProperties {
        mqttUserProperty("authorization", "tmelogin")
        mqttUserProperty("pubsub", "unicast")
    }
    mqttString(topic)
    writeByte(0) // QoS 0.
}

private fun mqttPacket(header: Int, fill: Buffer.() -> Unit): ByteString {
    val body = Buffer().apply(fill)
    return Buffer().apply {
        writeByte(header)
        mqttSection(body)
    }.readByteString()
}

private fun Buffer.mqttProperties(fill: Buffer.() -> Unit) = mqttSection(Buffer().apply(fill))

private fun Buffer.mqttUserProperty(name: String, value: String) {
    writeByte(0x26)
    mqttString(name)
    mqttString(value)
}

private fun Buffer.mqttString(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    writeShort(bytes.size)
    write(bytes)
}

private fun Buffer.mqttSection(body: Buffer) {
    var size = body.size.toInt()
    do {
        val value = size and 0x7f
        size = size ushr 7
        writeByte(value or if (size == 0) 0 else 0x80)
    } while (size != 0)
    writeAll(body)
}

private class MqttPacket(val header: Int, val body: MqttFields)

/** A WebSocket message can contain parts of multiple MQTT packets. */
private class MqttPacketStream(private val messages: Channel<ByteString>) {
    private val pending = Buffer()

    private suspend fun awaitBytes(size: Int) {
        while (pending.size < size) pending.write(messages.receive())
    }

    suspend fun read(): MqttPacket {
        awaitBytes(2)
        val header = pending.readByte().toInt() and 0xff
        var count = 0
        repeat(4) { index ->
            awaitBytes(1)
            val part = pending.readByte().toInt() and 0xff
            count = count or ((part and 0x7f) shl (7 * index))
            if (count > MAX_PACKET_BYTES) invalidQrMqtt()
            if (part < 0x80) {
                if (index > 0 && part == 0) invalidQrMqtt()
                awaitBytes(count)
                return MqttPacket(header, MqttFields(pending.readByteString(count.toLong())))
            }
        }
        invalidQrMqtt()
    }
}

private class MqttProperties {
    var eventType: String? = null
    var serverReference: String? = null
    var keepAlive: Int? = null
}

private class MqttFields(private val bytes: ByteString) {
    private var position = 0
    val exhausted: Boolean get() = position == bytes.size

    fun u8(): Int {
        if (exhausted) invalidQrMqtt()
        return bytes[position++].toInt() and 0xff
    }

    fun u16(): Int = (u8() shl 8) or u8()

    private fun variable(): Int {
        var value = 0
        repeat(4) { index ->
            val part = u8()
            value = value or ((part and 0x7f) shl (7 * index))
            if (part < 0x80) {
                if (index > 0 && part == 0) invalidQrMqtt()
                return value
            }
        }
        invalidQrMqtt()
    }

    private fun take(size: Int): ByteString {
        if (size > bytes.size - position) invalidQrMqtt()
        val value = bytes.substring(position, position + size)
        position += size
        return value
    }

    fun utf8(): String = decodeStrictUtf8(take(u16()))

    fun remainingText(): String = decodeStrictUtf8(take(bytes.size - position))

    fun properties(): MqttProperties {
        val properties = MqttFields(take(variable()))
        return MqttProperties().apply {
            while (!properties.exhausted) {
                when (properties.variable()) {
                    0x01, 0x24, 0x25, 0x28, 0x29, 0x2a -> properties.u8()
                    0x02, 0x11, 0x27 -> properties.take(4)
                    0x03, 0x08, 0x12, 0x15, 0x1a, 0x1f -> properties.utf8()
                    0x09, 0x16 -> properties.take(properties.u16())
                    0x0b -> properties.variable()
                    0x13 -> {
                        if (keepAlive != null) invalidQrMqtt()
                        keepAlive = properties.u16()
                    }
                    0x1c -> {
                        if (serverReference != null) invalidQrMqtt()
                        serverReference = properties.utf8()
                    }
                    0x21, 0x22, 0x23 -> properties.u16()
                    0x26 -> {
                        val name = properties.utf8()
                        val value = properties.utf8()
                        if (name == "type") {
                            if (eventType != null) invalidQrMqtt()
                            eventType = value
                        }
                    }
                    else -> invalidQrMqtt()
                }
            }
        }
    }
}

private fun decodeStrictUtf8(bytes: ByteString): String = try {
    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
} catch (_: CharacterCodingException) { invalidQrMqtt() }

private fun invalidQrMqtt(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE)
