package com.nomadlouie.desktop.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log

/**
 * Plays the Selkies Opus audio stream (0x01 messages, already reduced to
 * ordered Opus frames by the client's RED parser) through MediaCodec and an
 * AudioTrack.
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
    }

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    var running = false
        private set
    private var thread: Thread? = null
    private val queue = java.util.ArrayDeque<ByteArray>()
    private val lock = Object()

    val available: Boolean by lazy {
        try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).release()
            true
        } catch (e: Exception) {
            Log.w(TAG, "No Opus decoder on this device: ${e.message}")
            false
        }
    }

    @Synchronized
    fun start() {
        if (running || !available) return
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS)
            val mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            mc.configure(format, null, null, 0)
            mc.start()

            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf * 4, 65536))
                .build()
            at.play()

            codec = mc
            track = at
            running = true
            thread = Thread({ loop(mc, at) }, "OpusAudio").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio start failed: ${e.message}")
            stop()
        }
    }

    fun submit(frame: ByteArray) {
        if (!running) return
        synchronized(lock) {
            if (queue.size < 64) queue.addLast(frame)
        }
    }

    private fun loop(mc: MediaCodec, at: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val frame = synchronized(lock) { queue.pollFirst() }
            if (frame == null) {
                try {
                    Thread.sleep(4)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            try {
                var index = mc.dequeueInputBuffer(20_000)
                if (index >= 0) {
                    val buf = mc.getInputBuffer(index) ?: continue
                    if (frame.size > buf.capacity()) continue
                    buf.clear()
                    buf.put(frame)
                    mc.queueInputBuffer(index, 0, frame.size, 0, 0)
                }
                // Drain whatever decoded output is ready.
                var outIndex = mc.dequeueOutputBuffer(info, 0)
                while (outIndex >= 0) {
                    val out = mc.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(pcm)
                        at.write(pcm, 0, pcm.size)
                    }
                    mc.releaseOutputBuffer(outIndex, false)
                    outIndex = mc.dequeueOutputBuffer(info, 0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "audio decode error: ${e.message}")
                return
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        try {
            track?.stop()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        synchronized(lock) { queue.clear() }
    }
}
