package com.nomadlouie.desktop.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Decodes the Selkies video stream (Annex-B H.264/H.265/VP8/VP9/AV1 frames
 * from 0x04 messages) straight onto a Surface with MediaCodec in synchronous
 * mode on a dedicated thread.
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
        if (released) return
        while (true) {
            val frame = synchronized(lock) { pending.removeFirstOrNull() } ?: return
            try {
                ensureConfigured(frame)
                val mc = codec ?: return
                val index = mc.dequeueInputBuffer(25_000)
                if (index < 0) {
                    // No input buffer free yet: retry shortly rather than lose the frame.
                    synchronized(lock) { pending.addFirst(frame) }
                    handler.postDelayed({ drain() }, 8)
                    return
                }
                val buffer = mc.getInputBuffer(index)
                if (buffer == null || frame.payload.size > buffer.capacity()) {
                    callback?.onFrameDropped(frame.frameId)
                    continue
                }
                buffer.clear()
                buffer.put(frame.payload)
                val flags = if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                mc.queueInputBuffer(index, 0, frame.payload.size, frame.frameId.toLong() * 1000, flags)

                // Release everything already decoded so it hits the surface.
                val info = MediaCodec.BufferInfo()
                var out = mc.dequeueOutputBuffer(info, 0)
                var guard = 0
                while (out >= 0 && guard < 8) {
                    mc.releaseOutputBuffer(out, true)
                    callback?.onFrameDecoded(frame.frameId)
                    out = mc.dequeueOutputBuffer(info, 0)
                    guard++
                }
            } catch (e: Exception) {
                Log.e(TAG, "decode error: ${e.message}")
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
