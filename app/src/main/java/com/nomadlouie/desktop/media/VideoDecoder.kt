package com.nomadlouie.desktop.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes the Selkies video stream (Annex-B H.264/H.265/VP8/VP9/AV1 frames
 * from 0x04 messages) onto a Surface with MediaCodec in synchronous mode on a
 * dedicated thread.
 *
 * The format is configured with csd-0/csd-1 parameter sets parsed out of the
 * first key frame (the same information the web client reads to build its
 * codec string), so decoders that ignore in-band parameter sets still start.
 */
class VideoDecoder(surface: Surface) {

    interface Callback {
        fun onFrameDecoded(frameId: Int) {}
        fun onFrameDropped(frameId: Int) {}
        fun onCodecChanged(codec: String) {}
        fun onDecoderError(message: String) {}
        fun onDecoderNeedsKeyframe() {}
    }

    companion object {
        private const val TAG = "VideoDecoder"

        fun mimeFor(codec: String): String? = when (codec) {
            "h264" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "h265" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            "vp8" -> "video/x-vnd.on2.vp8"
            "vp9" -> "video/x-vnd.on2.vp9"
            "av1" -> "video/av01"
            else -> null
        }

        /** Splits an Annex-B stream into NAL units, each without its start code. */
        private fun annexbNals(bytes: ByteArray): List<ByteArray> {
            val nals = ArrayList<ByteArray>()
            val n = bytes.size
            var start = -1
            var i = 0
            while (i + 2 < n) {
                if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1) {
                    if (start >= 0) {
                        var end = i
                        while (end > start && bytes[end - 1].toInt() == 0) end--
                        if (end > start) nals.add(bytes.copyOfRange(start, end))
                    }
                    start = i + 3
                    i += 2
                }
                i++
            }
            if (start in 0 until n) nals.add(bytes.copyOfRange(start, n))
            return nals
        }

        private fun withStartCode(nal: ByteArray): ByteArray {
            val out = ByteArray(nal.size + 4)
            out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
            nal.copyInto(out, 4)
            return out
        }

        /** csd-0 and csd-1 for the mime, parsed from a key-frame payload. */
        private fun paramSets(payload: ByteArray, mime: String): Pair<ByteArray?, ByteArray?> {
            val nals = annexbNals(payload)
            return when (mime) {
                MediaFormat.MIMETYPE_VIDEO_AVC -> {
                    val sps = nals.filter { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
                    val pps = nals.filter { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
                    if (sps.isEmpty() || pps.isEmpty()) return null to null
                    val csd0 = (sps + pps).flatMap { withStartCode(it).toList() }.toByteArray()
                    val csd1 = pps.flatMap { withStartCode(it).toList() }.toByteArray()
                    csd0 to csd1
                }
                MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                    fun nalType(nal: ByteArray): Int =
                        if (nal.isEmpty()) -1 else (nal[0].toInt() shr 1) and 0x3F
                    val vps = nals.filter { nalType(it) == 32 }
                    val sps = nals.filter { nalType(it) == 33 }
                    val pps = nals.filter { nalType(it) == 34 }
                    if (sps.isEmpty() || pps.isEmpty()) return null to null
                    val csd0 = (vps + sps + pps).flatMap { withStartCode(it).toList() }.toByteArray()
                    csd0 to null
                }
                else -> null to null
            }
        }
    }

    var callback: Callback? = null

    @Volatile
    private var surface: Surface = surface

    private val thread = HandlerThread("VideoDecoder").apply { start() }
    private val handler = Handler(thread.looper)

    private var codec: MediaCodec? = null
    private var currentMime: String? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private var released = false

    var decodedFrames = 0
        private set
    var droppedFrames = 0
        private set

    private val pending = ArrayDeque<Frame>()
    private val lock = Object()

    data class Frame(
        val codec: String,
        val keyframe: Boolean,
        val frameId: Int,
        val width: Int,
        val height: Int,
        val payload: ByteArray,
    )

    /** Swaps the render target (e.g. after the TextureView is recreated). */
    fun setSurface(newSurface: Surface) {
        if (newSurface == surface) return
        surface = newSurface
        synchronized(lock) { pending.clear() }
        handler.post {
            reset()
            callback?.onDecoderNeedsKeyframe()
        }
    }

    /** Forces a full reconfigure on the next frame (after a pipeline reset). */
    fun hardReset() {
        if (released) return
        synchronized(lock) { pending.clear() }
        handler.post {
            reset()
            callback?.onDecoderNeedsKeyframe()
        }
    }

    fun submit(frame: Frame) {
        if (released) return
        if (frame.width <= 0 || frame.height <= 0 || frame.payload.isEmpty()) {
            droppedFrames++
            callback?.onFrameDropped(frame.frameId)
            return
        }
        synchronized(lock) {
            while (pending.size >= 6) {
                val dropped = if (pending.first().keyframe && pending.size > 1) {
                    pending.removeAt(1)
                } else {
                    pending.removeFirst()
                }
                droppedFrames++
                callback?.onFrameDropped(dropped.frameId)
            }
            pending.addLast(frame)
        }
        handler.post { drain() }
    }

    private fun drain() {
        if (released) return
        while (true) {
            val frame = synchronized(lock) { pending.removeFirstOrNull() } ?: return
            try {
                ensureConfigured(frame)
                val mc = codec ?: return
                val index = mc.dequeueInputBuffer(25_000)
                if (index < 0) {
                    synchronized(lock) { pending.addFirst(frame) }
                    handler.postDelayed({ drain() }, 8)
                    return
                }
                val buffer = mc.getInputBuffer(index)
                if (buffer == null || frame.payload.size > buffer.capacity()) {
                    droppedFrames++
                    callback?.onFrameDropped(frame.frameId)
                    continue
                }
                buffer.clear()
                buffer.put(frame.payload)
                mc.queueInputBuffer(index, 0, frame.payload.size, frame.frameId.toLong() * 1000, 0)

                val info = MediaCodec.BufferInfo()
                var out = mc.dequeueOutputBuffer(info, 0)
                var guard = 0
                while (out >= 0 && guard < 8) {
                    mc.releaseOutputBuffer(out, true)
                    decodedFrames++
                    callback?.onFrameDecoded(frame.frameId)
                    out = mc.dequeueOutputBuffer(info, 0)
                    guard++
                }
            } catch (e: Exception) {
                Log.e(TAG, "decode error: ${e.message}")
                droppedFrames++
                callback?.onFrameDropped(frame.frameId)
                callback?.onDecoderError(e.message ?: "decode error")
                reset()
                callback?.onDecoderNeedsKeyframe()
                return
            }
        }
    }

    private fun ensureConfigured(frame: Frame) {
        val mime = mimeFor(frame.codec)
            ?: throw IllegalArgumentException("unsupported codec ${frame.codec}")
        val needsReconfigure = codec == null || currentMime != mime ||
            currentWidth != frame.width || currentHeight != frame.height
        if (needsReconfigure) {
            reset()
            val format = MediaFormat.createVideoFormat(mime, frame.width, frame.height)
            if (frame.keyframe) {
                val (csd0, csd1) = paramSets(frame.payload, mime)
                csd0?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                csd1?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            }
            val mc = MediaCodec.createDecoderByType(mime)
            mc.configure(format, surface, null, 0)
            mc.start()
            codec = mc
            currentMime = mime
            currentWidth = frame.width
            currentHeight = frame.height
            callback?.onCodecChanged(frame.codec)
        }
    }

    private fun reset() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        currentMime = null
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        synchronized(lock) { pending.clear() }
        reset()
        thread.quitSafely()
    }
}
