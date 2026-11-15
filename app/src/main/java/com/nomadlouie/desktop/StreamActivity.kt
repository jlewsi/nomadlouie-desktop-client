package com.nomadlouie.desktop

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nomadlouie.desktop.databinding.ActivityStreamBinding
import com.nomadlouie.desktop.media.AudioPlayer
import com.nomadlouie.desktop.media.VideoDecoder
import com.nomadlouie.desktop.selkies.InputSender
import com.nomadlouie.desktop.selkies.Keysyms
import com.nomadlouie.desktop.selkies.SelkiesClient
import com.nomadlouie.desktop.ui.StreamView
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class StreamActivity : AppCompatActivity(), StreamView.Listener, SelkiesClient.Listener {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        private const val TAG = "StreamActivity"
        private const val ACK_INTERVAL_MS = 250L
        private const val WATCHDOG_MS = 4000L
        private const val BACKSPACE_KEYSYM = 0xFF08
    }

    private lateinit var binding: ActivityStreamBinding
    private lateinit var client: SelkiesClient
    private lateinit var input: InputSender
    private val audio = AudioPlayer()

    private var decoder: VideoDecoder? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastFrameId = -1
    private var lastFrameAt = 0L
    private var gotAnyVideo = false
    private val frameCount = AtomicInteger(0)
    private val byteCount = AtomicLong(0)
    private var lastStatsAt = 0L
    private var currentCodec = "h264"
    private var imeBuffer = ""
    private var modifierIndex = 0

    private val modifierKeysyms = intArrayOf(
        Keysyms.CTRL_L, Keysyms.ALT_L, Keysyms.SHIFT_L, Keysyms.SUPER_L
    )

    private val ackLoop = object : Runnable {
        override fun run() {
            val id = lastFrameId
            if (id >= 0) {
                val held = (System.currentTimeMillis() - lastFrameAt).coerceAtLeast(0)
                client.frameAck(id, held)
            }
            handler.postDelayed(this, ACK_INTERVAL_MS)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gotAnyVideo) {
                client.startVideo()
                client.requestKeyframe()
                handler.postDelayed(this, WATCHDOG_MS)
            }
        }
    }

    private val statsLoop = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val elapsed = (now - lastStatsAt) / 1000.0
            if (lastStatsAt > 0 && elapsed > 0) {
                val fps = frameCount.getAndSet(0) / elapsed
                val kbps = byteCount.getAndSet(0) * 8 / elapsed / 1000.0
                binding.statusOverlay.text =
                    "%s %.0f fps %.0f kbps".format(currentCodec.uppercase(), fps, kbps)
            }
            lastStatsAt = now
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }
        val user = intent.getStringExtra(EXTRA_USER) ?: ""
        val pass = intent.getStringExtra(EXTRA_PASS) ?: ""

        binding.streamView.listener = this
        binding.streamView.onSurfaceReady = {
            if (decoder == null) {
                attachDecoder()
            } else {
                decoder?.setSurface(binding.streamView.videoSurface)
            }
        }

        client = SelkiesClient(url, user, pass, this)
        input = InputSender(client)

        binding.btnKeyboard.setOnClickListener { toggleIme() }
        binding.btnTrackpad.setOnClickListener { toggleTrackpad() }
        binding.btnModifiers.setOnClickListener { cycleStickyModifier() }
        binding.btnPaste.setOnClickListener { pasteIntoRemote() }
        binding.btnSendClipboard.setOnClickListener { sendClipboard() }
        binding.btnFit.setOnClickListener { fitResolution() }
        binding.btnDisconnect.setOnClickListener { finish() }

        setupImeCapture()

        handler.post(statsLoop)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        client.connect()
        handler.postDelayed(ackLoop, ACK_INTERVAL_MS)
    }

    private fun attachDecoder() {
        val surface = binding.streamView.videoSurface
        if (!surface.isValid) return
        val dec = VideoDecoder(surface)
        dec.callback = decoderCallback
        decoder = dec
        client.requestKeyframe()
        client.setPointerEcho(binding.streamView.trackpadMode)
        client.setNativeCursorRendering(!binding.streamView.trackpadMode)
        fitResolution()
    }

    private val decoderCallback = object : VideoDecoder.Callback {
        override fun onFrameDropped(frameId: Int) {
            client.lostFrame(frameId)
        }

        override fun onDecoderNeedsKeyframe() {
            client.requestKeyframe()
        }

        override fun onCodecChanged(codec: String) {
            currentCodec = codec
            binding.streamView.clearJpegBuffer()
        }

        override fun onDecoderError(message: String) {
            Log.e(TAG, "decoder error: $message")
        }
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        val flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val decor = window.decorView
        decor.systemUiVisibility = flags
        decor.setOnSystemUiVisibilityChangeListener { decor.systemUiVisibility = flags }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun toggleTrackpad() {
        binding.streamView.trackpadMode = !binding.streamView.trackpadMode
        binding.streamView.releaseAllButtons()
        client.setPointerEcho(binding.streamView.trackpadMode)
        client.setNativeCursorRendering(!binding.streamView.trackpadMode)
        Toast.makeText(
            this,
            if (binding.streamView.trackpadMode) "Trackpad mode" else "Direct touch mode",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val ime: EditText = binding.imeCapture
        if (ime.hasFocus()) {
            imm.hideSoftInputFromWindow(ime.windowToken, 0)
            ime.clearFocus()
        } else {
            ime.requestFocus()
            imm.showSoftInput(ime, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupImeCapture() {
        val ime: EditText = binding.imeCapture
        ime.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString() ?: return
                if (text.length > imeBuffer.length) {
                    input.typeText(text.substring(imeBuffer.length))
                } else if (text.length < imeBuffer.length) {
                    repeat(imeBuffer.length - text.length) {
                        input.pressKey(BACKSPACE_KEYSYM, -100)
                        input.releaseKey(-100)
                    }
                }
                imeBuffer = text
                if (imeBuffer.length > 100) {
                    ime.setText("")
                    imeBuffer = ""
                }
            }
        })
    }

    private fun cycleStickyModifier() {
        val keysym = modifierKeysyms[modifierIndex]
        val on = input.toggleSticky(keysym)
        val label = when (keysym) {
            Keysyms.CTRL_L -> getString(R.string.ctrl)
            Keysyms.ALT_L -> getString(R.string.alt)
            Keysyms.SHIFT_L -> getString(R.string.shift)
            else -> getString(R.string.super_key)
        }
        binding.btnModifiers.text = if (on) "$label •" else label
        if (!on) modifierIndex = (modifierIndex + 1) % modifierKeysyms.size
    }

    private fun pasteIntoRemote() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        input.typeText(text)
    }

    private fun sendClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        client.sendClipboardText(text)
        Toast.makeText(this, "Clipboard sent", Toast.LENGTH_SHORT).show()
    }

    private fun fitResolution() {
        if (binding.streamView.width <= 0) return
        val metrics = resources.displayMetrics
        client.requestResolution(metrics.widthPixels, metrics.heightPixels)
        client.sendClientSize(metrics.widthPixels, metrics.heightPixels)
    }

    override fun onPointerMoveAbsolute(x: Int, y: Int, mask: Int) {
        client.pointerMoveAbsolute(x, y, mask)
    }

    override fun onPointerMoveRelative(dx: Int, dy: Int, mask: Int) {
        client.pointerMoveRelative(dx, dy, mask)
    }

    override fun onPointerButtons(mask: Int) {
        client.pointerButtonsRelative(mask)
    }

    override fun onWheel(notches: Int, horizontal: Boolean) {
        client.wheelPulse(notches, horizontal)
    }

    override fun onStickyModifier(keysym: Int): Boolean = input.hasSticky(keysym)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK && event.repeatCount == 0) {
            val keysym = Keysyms.fromKeyEvent(event)
            if (keysym != null) {
                input.pressKey(keysym, keyCode)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) {
            val keysym = Keysyms.fromKeyEvent(event)
            if (keysym != null) {
                input.releaseKey(keyCode)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPointerEcho(x: Int, y: Int, scale: Float) {
        runOnUiThread { binding.streamView.onPointerEcho(x, y) }
    }

    override fun onVideoFrame(codec: String, keyframe: Boolean, frameId: Int, width: Int, height: Int, payload: ByteArray) {
        gotAnyVideo = true
        lastFrameId = frameId
        lastFrameAt = System.currentTimeMillis()
        frameCount.incrementAndGet()
        byteCount.addAndGet(payload.size.toLong())
        runOnUiThread { binding.streamView.setStreamSize(width, height) }
        decoder?.submit(VideoDecoder.Frame(codec, keyframe, frameId, width, height, payload))
    }

    override fun onJpegStripe(frameId: Int, stripeY: Int, payload: ByteArray) {
        gotAnyVideo = true
        frameCount.incrementAndGet()
        byteCount.addAndGet(payload.size.toLong())
        runOnUiThread { binding.streamView.drawJpegStripe(stripeY, payload) }
    }

    override fun onAudioOpus(frames: List<ByteArray>) {
        if (!audio.running && frames.isNotEmpty()) audio.start()
        for (f in frames) audio.submit(f)
    }

    override fun onClipboardText(text: String) {
        runOnUiThread {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("selkies", text))
        }
    }

    override fun onStreamResolution(width: Int, height: Int) {
        runOnUiThread { binding.streamView.setStreamSize(width, height) }
    }

    override fun onPipelineReset() {
        decoder?.hardReset()
        runOnUiThread { binding.streamView.clearJpegBuffer() }
    }

    override fun onKilled(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "Server closed session: $reason", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        input.releaseAll()
        input.destroy()
        client.close()
        decoder?.release()
        audio.stop()
    }
}
