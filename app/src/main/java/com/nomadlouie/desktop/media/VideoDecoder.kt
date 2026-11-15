package com.nomadlouie.desktop.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Decodes the Selkies video stream (Annex-B H.264/H.265/VP8/VP9/AV1 frames
 * from 0x04 messages) straight onto a Surface with MediaCodec.
 *
 * Frames arrive complete (header stripped by SelkiesClient), so each payload
 * is one access-unit. Parameter sets ride in-band on key frames, which is all
 * an Android decoder configured without csd-0/csd-1 needs.
 */
class VideoDecoder(surface: Surface) {

    interface Callback {
        fun onFrameDecoded(frameId: Int) {}
        fun onFrameDropped(frameId: Int) {}
        fun onCodecChanged(codec: String) {}
        fun onDecoderError(message: String) {}
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
    }

    var callback: Callback? = null

    private val thread = HandlerThread("VideoDecoder").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var surface: Surface = surface

    private var codec: MediaCodec? = null
    private var currentMime: String? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private var released = false

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

    /** Swaps the render target (e.g. after the SurfaceView is recreated on rotation). */
    fun setSurface(newSurface: Surface) {
        if (newSurface == surface) return
        surface = newSurface
        synchronized(lock) { pending.clear() }
        handler.post { reset() }
    }

    fun submit(frame: Frame) {
        if (released) return
        synchronized(lock) {
            // Bound the pipeline: drop oldest non-keyframes when the decoder lags.
            while (pending.size >= 6) {
                val dropped = if (pending.first().keyframe && pending.size > 1) {
                    pending.removeAt(1)
                } else {
                    pending.removeFirst()
                }
                callback?.onFrameDropped(dropped.frameId)
            }
            pending.addLast(frame)
        }
        handler.post { drain() }
    }

    private fun drain() {
        while (true) {
            val frame = synchronized(lock) { pending.removeFirstOrNull() } ?: return
            try {
                ensureConfigured(frame)
                val mc = codec ?: return
                val index = mc.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = mc.getInputBuffer(index) ?: return
                    if (frame.payload.size > buffer.capacity()) {
                        callback?.onFrameDropped(frame.frameId)
                        continue
                    }
                    buffer.clear()
                    buffer.put(frame.payload)
                    val flags = if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    mc.queueInputBuffer(index, 0, frame.payload.size, frame.frameId.toLong() * 1000, flags)
                } else {
                    callback?.onFrameDropped(frame.frameId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "decode error: ${e.message}")
                callback?.onFrameDropped(frame.frameId)
                reset()
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
            val mc = MediaCodec.createDecoderByType(mime)
            mc.configure(format, this.surface, null, 0)
            mc.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {}

                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        mc.releaseOutputBuffer(index, true)
                    } catch (_: Exception) {
                    }
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "codec error: ${e.message}")
                    callback?.onDecoderError(e.message ?: "codec error")
                }

                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {}
            })
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

    /** Forces a full reconfigure on the next frame (after a pipeline reset). */
    fun hardReset() {
        if (released) return
        synchronized(lock) { pending.clear() }
        handler.post { reset() }
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
