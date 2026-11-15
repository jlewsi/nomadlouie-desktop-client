package com.nomadlouie.desktop.selkies

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client for the Selkies v2 WebSocket transport (`wss://host/api/websockets`).
 *
 * Wire summary (from selkies-project/selkies, crates/selkies/src/ws/protocol.rs and
 * web/selkies-web-core/selkies-ws-core.ts):
 *
 * Text frames are control verbs. Binary frames are typed by their first byte:
 *  - 0x01 audio: Opus, `[0x01, 0x00] + opus` plain, or with RED redundancy
 *    `[0x01, n_red(+0x80 quiet bit), pts32] + n_red*(4-byte hdr) + 1-byte primary hdr + blocks`
 *  - 0x03 JPEG stripe: `[0x03, u8 reserved, u16 frameId, u16 stripeY] + jpeg`
 *  - 0x04 full video frame: `[0x04, u8 codec<<4|keykind, u16 frameId, u16 stripeY,
 *    u16 width, u16 height, u16 refFrameId] + annexB coded data`
 *  - 0x05 gzip-wrapped control text, 0x07 padding (drop)
 *
 * Client sends: `SETTINGS,{json}`, `r,WxH,displayId`, `START_VIDEO`, `STOP_VIDEO`,
 * `START_AUDIO`, `STOP_AUDIO`, `REQUEST_KEYFRAME`, `LOST_FRAME <id>`,
 * `CLIENT_FRAME_ACK <id> <heldMs>`, `cr`, `REQUEST_CLIPBOARD`, `cws,<id>,<total>`,
 * `cwd,<id>,<b64>`, `cwe,<id>`, `SET_NATIVE_CURSOR_RENDERING,<0|1>`,
 * `_pointer_echo,<0|1>` and the input verbs (`kd,` `ku,` `kh,` `kr`, `m,` `m2,`).
 */
class SelkiesClient(
    baseUrl: String,
    username: String,
    password: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected() {}
        fun onServerSettings(settings: JSONObject) {}
        fun onVideoFrame(codec: String, keyframe: Boolean, frameId: Int, width: Int, height: Int, payload: ByteArray) {}
        fun onJpegStripe(frameId: Int, stripeY: Int, payload: ByteArray) {}
        fun onAudioOpus(frames: List<ByteArray>) {}
        fun onClipboardText(text: String) {}
        fun onPointerEcho(x: Int, y: Int, scale: Float) {}
        fun onStreamResolution(width: Int, height: Int) {}
        fun onPipelineReset() {}
        fun onKilled(reason: String) {}
        fun onClosed() {}
        fun onError(message: String) {}
    }

    companion object {
        private const val TAG = "SelkiesClient"
        private const val OPCODE_AUDIO = 0x01
        private const val OPCODE_JPEG = 0x03
        private const val OPCODE_VIDEO = 0x04
        private const val OPCODE_GZIP = 0x05
        private const val OPCODE_PADDING = 0x07
        private const val AUDIO_QUIET = 0x80
        val WIRE_CODECS = mapOf(1 to "h264", 2 to "vp8", 3 to "vp9", 4 to "av1", 5 to "h265")
    }

    private val normalizer = baseUrl.trimEnd('/')
    private val wsUrl = if (normalizer.endsWith("/api/websockets")) normalizer else "$normalizer/api/websockets"

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private val started = AtomicBoolean(false)
    private val pointerSeq = AtomicInteger(0)
    private var clipboardTransferId = 0

    fun connect() {
        if (!started.compareAndSet(false, true)) return
        val credentials = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Basic $credentials")
            .build()
        listener.onConnected()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendInitialSettings()
                send("cr")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinary(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val msg = when (code) {
                    401 -> "Authentication failed (HTTP 401). Check username/password."
                    404 -> "Endpoint not found (HTTP 404). Is this a Selkies/Webtop server?"
                    else -> t.message ?: "Connection failed"
                }
                Log.e(TAG, "WS failure: $msg", t)
                listener.onError(msg)
                listener.onClosed()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed()
            }
        })
    }

    fun close() {
        socket?.close(1000, "client closing")
        socket = null
        client.dispatcher.executorService.shutdown()
    }

    private fun send(text: String): Boolean {
        val s = socket ?: return false
        return s.send(text)
    }

    private fun sendInitialSettings() {
        val json = JSONObject()
        json.put("displayId", "primary")
        json.put("manual_resolution", false)
        json.put("audioRedundancy", true)
        json.put("color_scheme", "dark")
        send("SETTINGS,$json")
    }

    fun requestResolution(width: Int, height: Int) {
        val w = width - (width % 2)
        val h = height - (height % 2)
        send("r,${w}x${h},primary")
    }

    fun sendClientSize(width: Int, height: Int) {
        val json = JSONObject()
        json.put("displayId", "primary")
        json.put("manual_resolution", false)
        json.put("initialClientWidth", width - (width % 2))
        json.put("initialClientHeight", height - (height % 2))
        send("SETTINGS,$json")
    }

    fun startVideo() = send("START_VIDEO")
    fun stopVideo() = send("STOP_VIDEO")
    fun startAudio() = send("START_AUDIO")
    fun stopAudio() = send("STOP_AUDIO")
    fun requestKeyframe() = send("REQUEST_KEYFRAME")
    fun lostFrame(frameId: Int) = send("LOST_FRAME $frameId")
    fun frameAck(frameId: Int, heldMs: Long) = send("CLIENT_FRAME_ACK $frameId $heldMs")
    fun requestClipboard() = send("REQUEST_CLIPBOARD")

    fun setPointerEcho(enabled: Boolean) = send("_pointer_echo,${if (enabled) 1 else 0}")
    fun setNativeCursorRendering(enabled: Boolean) = send("SET_NATIVE_CURSOR_RENDERING,${if (enabled) 1 else 0}")

    fun keyDown(keysym: Int) = send("kd,$keysym")
    fun keyUp(keysym: Int) = send("ku,$keysym")
    fun keysHeld(keysyms: List<Int>) {
        if (keysyms.isEmpty()) {
            send("kr")
        } else {
            send("kh," + keysyms.joinToString(","))
        }
    }

    fun pointerMoveAbsolute(x: Int, y: Int, mask: Int) {
        send("m,$x,$y,$mask,0,${pointerSeq.incrementAndGet()}")
    }

    fun pointerMoveRelative(dx: Int, dy: Int, mask: Int) {
        send("m2,$dx,$dy,$mask,0,${pointerSeq.incrementAndGet()}")
    }

    fun pointerButtonsRelative(mask: Int) {
        send("m2,0,0,$mask,0,${pointerSeq.incrementAndGet()}")
    }

    /** Vertical wheel pulse: rising edge on bit 3 (down) or 4 (up), then release. */
    fun wheelPulse(notches: Int, horizontal: Boolean = false) {
        val magnitude = maxOf(1, Math.abs(notches))
        val down = notches > 0
        val button = if (horizontal) (if (down) 7 else 6) else (if (down) 3 else 4)
        val mask = 1 shl button
        pointerButtonsRelative(0 and mask.inv())
        pointerButtonsRelative(mask)
        pointerButtonsRelative(0)
    }

    fun sendClipboardText(text: String) {
        val id = ++clipboardTransferId
        val bytes = text.toByteArray(Charsets.UTF_8)
        send("cws,$id,${bytes.size}")
        var offset = 0
        val chunk = 16 * 1024
        while (offset < bytes.size) {
            val end = minOf(offset + chunk, bytes.size)
            val b64 = Base64.encodeToString(bytes, offset, end - offset, Base64.NO_WRAP)
            send("cwd,$id,$b64")
            offset = end
        }
        send("cwe,$id")
    }

    private fun handleText(text: String) {
        when {
            text.startsWith("KILL ") -> listener.onKilled(text.substring(5))
            text.startsWith("clipboard,") -> {
                val decoded = try {
                    Base64.decode(text.substring(10), Base64.DEFAULT).toString(Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    text.substring(10)
                }
                listener.onClipboardText(decoded)
            }
            text.startsWith("clipboard_start,") || text.startsWith("clipboard_data,") ||
                text.startsWith("clipboard_finish") -> decodeChunkedClipboard(text)
            text.startsWith("system,") -> {
                try {
                    val obj = JSONObject(text.substring(7))
                    val action = obj.optString("action")
                    if (action.startsWith("pointer,")) {
                        val parts = action.split(",")
                        if (parts.size >= 4) {
                            listener.onPointerEcho(
                                parts[2].toIntOrNull() ?: 0,
                                parts[3].toIntOrNull() ?: 0,
                                parts.getOrNull(4)?.toFloatOrNull() ?: 1f,
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }
            text.startsWith("PIPELINE_RESETTING") -> listener.onPipelineReset()
            text.startsWith("{") -> handleJson(text)
        }
    }

    private var chunkedMime = "text/plain"
    private val chunkedBuffer = StringBuilder()

    private fun decodeChunkedClipboard(text: String) {
        when {
            text.startsWith("clipboard_start,") -> {
                chunkedMime = text.split(",").getOrNull(1) ?: "text/plain"
                chunkedBuffer.setLength(0)
            }
            text.startsWith("clipboard_data,") -> chunkedBuffer.append(text.substring(15))
            text.startsWith("clipboard_finish") -> {
                if (chunkedMime == "text/plain") {
                    val decoded = try {
                        Base64.decode(chunkedBuffer.toString(), Base64.DEFAULT).toString(Charsets.UTF_8)
                    } catch (_: IllegalArgumentException) {
                        ""
                    }
                    if (decoded.isNotEmpty()) listener.onClipboardText(decoded)
                }
                chunkedBuffer.setLength(0)
            }
        }
    }

    private fun handleJson(text: String) {
        try {
            val obj = JSONObject(text)
            when (obj.optString("type")) {
                "server_settings" -> {
                    listener.onServerSettings(obj)
                    startVideo()
                    startAudio()
                }
                "stream_resolution" -> {
                    val w = obj.optInt("width")
                    val h = obj.optInt("height")
                    if (w > 0 && h > 0) listener.onStreamResolution(w, h)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun handleBinary(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0].toInt() and 0xFF) {
            OPCODE_PADDING -> return
            OPCODE_GZIP -> {
                val text = inflate(data)
                if (text != null) handleText(text)
            }
            OPCODE_AUDIO -> listener.onAudioOpus(extractOpusFrames(data))
            OPCODE_JPEG -> {
                if (data.size < 7) return
                val frameId = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                val stripeY = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
                listener.onJpegStripe(frameId, stripeY, data.copyOfRange(6, data.size))
            }
            OPCODE_VIDEO -> {
                if (data.size < 13) return
                val typeByte = data[1].toInt() and 0xFF
                val codec = WIRE_CODECS[typeByte shr 4] ?: "h264"
                val keyframe = (typeByte & 0x0F) == 0x01
                val frameId = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                val width = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
                val height = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
                val payload = data.copyOfRange(12, data.size)
                if (payload.isNotEmpty()) {
                    listener.onVideoFrame(codec, keyframe, frameId, width, height, payload)
                }
            }
        }
    }

    private fun inflate(data: ByteArray): String? = try {
        GZIPInputStream(ByteArrayInputStream(data, 1, data.size - 1)).readBytes().toString(Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "gzip inflate failed: ${e.message}")
        null
    }

    /**
     * Parses an audio message body into ordered Opus frames, using RED redundancy
     * exactly as the web client does (selkies-ws-core.ts extractOpusFrames):
     * every frame newer than the last played one is taken, oldest-first.
     */
    private fun extractOpusFrames(data: ByteArray): List<ByteArray> {
        if (data.size <= 2) return emptyList()
        val nRed = (data[1].toInt() and 0xFF) and AUDIO_QUIET.inv()
        if (nRed == 0) return listOf(data.copyOfRange(2, data.size))
        if (data.size < 6 + nRed * 4 + 1) return emptyList()
        val pts = ((data[2].toInt() and 0xFF) shl 24) or ((data[3].toInt() and 0xFF) shl 16) or
            ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        var pos = 6
        val offsets = IntArray(nRed)
        val lens = IntArray(nRed)
        for (i in 0 until nRed) {
            val field = ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            offsets[i] = (field shr 10) and 0x3FFF
            lens[i] = field and 0x3FF
            pos += 4
        }
        pos += 1
        var declared = pos
        for (i in 0 until nRed) declared += lens[i]
        if (declared > data.size) {
            lastAudioTs = -1L
            return emptyList()
        }
        val blockPts = IntArray(nRed + 1)
        val blockStart = IntArray(nRed + 1)
        val blockLen = IntArray(nRed + 1)
        for (i in 0 until nRed) {
            blockPts[i] = (pts - offsets[i])
            blockStart[i] = pos
            blockLen[i] = lens[i]
            pos += lens[i]
        }
        blockPts[nRed] = pts
        blockStart[nRed] = pos
        blockLen[nRed] = data.size - pos
        if (lastAudioTs < 0) {
            lastAudioTs = pts.toLong() and 0xFFFFFFFFL
            return listOf(data.copyOfRange(blockStart[nRed], blockStart[nRed] + blockLen[nRed]))
        }
        val frames = ArrayList<ByteArray>(nRed + 1)
        var last = lastAudioTs
        for (i in 0..nRed) {
            val ts = blockPts[i].toLong() and 0xFFFFFFFFL
            val d = (ts - last) and 0xFFFFFFFFL
            if (d != 0L && d < 0x80000000L) {
                frames.add(data.copyOfRange(blockStart[i], blockStart[i] + blockLen[i]))
                last = ts
            }
        }
        lastAudioTs = last
        return frames
    }

    private var lastAudioTs = -1L
}
