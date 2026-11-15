package com.nomadlouie.desktop.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The stream surface: a letterboxed TextureView the video decoder renders
 * into, with all touch handling on top.
 *
 * TextureView (rather than SurfaceView) keeps the video inside the normal
 * view hierarchy, so the pointer echo and JPEG-stripe fallback drawn in
 * dispatchDraw are composited on top of the video, and surface recreation
 * on some devices (punch-hole, resized holders) is avoided.
 *
 * Two mouse modes:
 *  - Trackpad (default for phones): one finger moves the pointer relatively,
 *    tap = left click, two-finger drag = scroll, two-finger tap = right
 *    click, long-press then drag = left drag. The server-reported pointer is
 *    drawn as a local echo.
 *  - Direct: touch maps to absolute desktop coordinates; tap = click, drag =
 *    left drag, long-press = right click, two-finger drag = scroll.
 */
class StreamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onPointerMoveAbsolute(x: Int, y: Int, mask: Int)
        fun onPointerMoveRelative(dx: Int, dy: Int, mask: Int)
        fun onPointerButtons(mask: Int)
        fun onWheel(notches: Int, horizontal: Boolean)
        fun onStickyModifier(keysym: Int): Boolean
    }

    var listener: Listener? = null
    var onSurfaceReady: ((Surface) -> Unit)? = null
    var trackpadMode = true
    var streamWidth = 0
        private set
    var streamHeight = 0
        private set

    val videoSurface: Surface?
        get() = cachedSurface?.takeIf { it.isValid }

    private val textureView = TextureView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var cachedSurface: Surface? = null

    // Pointer echo (trackpad mode).
    private var echoX = 0f
    private var echoY = 0f
    private var echoValid = false
    private val echoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 79, 140, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val echoFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 79, 140, 255)
        style = Paint.Style.FILL
    }

    // JPEG stripe fallback compositing.
    private var jpegBitmap: Bitmap? = null
    private var jpegCanvas: Canvas? = null

    // Gesture state.
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragButton = -1
    private var longPressFired = false
    private var scrollAccumY = 0f
    private var scrollAccumX = 0f
    private var secondDownTime = 0L

    companion object {
        private const val TAP_TIMEOUT_MS = 250L
        private const val LONGPRESS_TIMEOUT_MS = 450L
        private const val TAP_SLOP_PX = 24f
        private const val SCROLL_NOTCH_PX = 56f
        private const val TRACKPAD_SENSITIVITY = 1.0f
        const val MASK_LEFT = 1
        const val MASK_MIDDLE = 2
        const val MASK_RIGHT = 4
    }

    private val longPressRunnable = Runnable {
        if (trackpadMode) {
            // Left drag begins.
            dragButton = MASK_LEFT
            longPressFired = true
            listener?.onPointerButtons(MASK_LEFT)
        } else {
            // Right click at the touch position.
            longPressFired = true
            val (x, y) = mapAbsolute(downX, downY)
            listener?.onPointerMoveAbsolute(x, y, 0)
            listener?.onPointerMoveAbsolute(x, y, MASK_RIGHT)
            listener?.onPointerMoveAbsolute(x, y, 0)
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
            val surface = Surface(st)
            cachedSurface = surface
            onSurfaceReady?.invoke(surface)
        }

        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
            val surface = Surface(st)
            cachedSurface = surface
            onSurfaceReady?.invoke(surface)
        }

        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
            cachedSurface = null
            return true
        }

        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
    }

    init {
        addView(
            textureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        setWillNotDraw(false)
        textureView.isOpaque = true
        textureView.keepScreenOn = true
        textureView.surfaceTextureListener = surfaceListener
    }

    fun setStreamSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == streamWidth && h == streamHeight)) return
        streamWidth = w
        streamHeight = h
        jpegBitmap?.recycle()
        jpegBitmap = null
        jpegCanvas = null
        post { layoutVideo() }
    }

    fun onPointerEcho(x: Int, y: Int) {
        val (vx, vy) = mapToView(x.toFloat(), y.toFloat())
        echoX = vx
        echoY = vy
        echoValid = trackpadMode
        postInvalidate()
    }

    fun releaseAllButtons() {
        if (dragButton >= 0) {
            listener?.onPointerButtons(0)
            dragButton = -1
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutVideo()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutVideo()
    }

    private fun layoutVideo() {
        if (streamWidth <= 0 || streamHeight <= 0 || width == 0 || height == 0) return
        val rect = videoRect()
        textureView.layout(
            rect.left.toInt(), rect.top.toInt(),
            rect.right.toInt(), rect.bottom.toInt()
        )
    }

    private fun videoRect(): RectF {
        if (streamWidth <= 0 || streamHeight <= 0) {
            return RectF(0f, 0f, width.toFloat(), height.toFloat())
        }
        val scale = min(width.toFloat() / streamWidth, height.toFloat() / streamHeight)
        val w = streamWidth * scale
        val h = streamHeight * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    /** View coordinates to stream coordinates. */
    private fun mapAbsolute(vx: Float, vy: Float): Pair<Int, Int> {
        val rect = videoRect()
        val x = ((vx - rect.left) / rect.width() * streamWidth).toInt()
        val y = ((vy - rect.top) / rect.height() * streamHeight).toInt()
        return Pair(x.coerceIn(0, max(0, streamWidth - 1)), y.coerceIn(0, max(0, streamHeight - 1)))
    }

    private fun mapToView(x: Float, y: Float): Pair<Float, Float> {
        val rect = videoRect()
        val vx = rect.left + (x / max(1, streamWidth)) * rect.width()
        val vy = rect.top + (y / max(1, streamHeight)) * rect.height()
        return Pair(vx, vy)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val bmp = jpegBitmap
        if (bmp != null) {
            val rect = videoRect()
            canvas.drawBitmap(bmp, null, rect, null)
        }
        if (trackpadMode && echoValid) {
            canvas.drawCircle(echoX, echoY, 18f, echoFillPaint)
            canvas.drawCircle(echoX, echoY, 18f, echoPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                longPressFired = false
                dragButton = -1
                scrollAccumX = 0f
                scrollAccumY = 0f
                handler.postDelayed(longPressRunnable, LONGPRESS_TIMEOUT_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    handler.removeCallbacks(longPressRunnable)
                    secondDownTime = System.currentTimeMillis()
                    lastX = (event.getX(0) + event.getX(1)) / 2f
                    lastY = (event.getY(0) + event.getY(1)) / 2f
                    scrollAccumX = 0f
                    scrollAccumY = 0f
                    if (dragButton >= 0) {
                        listener?.onPointerButtons(0)
                        dragButton = -1
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val pointers = event.pointerCount
                if (pointers >= 2) {
                    handleScroll(event)
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    val travel = abs(event.x - downX) + abs(event.y - downY)
                    if (trackpadMode) {
                        if (dragButton >= 0) {
                            sendRelativeDrag(dx, dy)
                        } else {
                            sendRelativeMove(dx, dy)
                        }
                    } else {
                        if (dragButton >= 0) {
                            val (x, y) = mapAbsolute(event.x, event.y)
                            listener?.onPointerMoveAbsolute(x, y, dragButton)
                        } else if (travel > TAP_SLOP_PX) {
                            val (x, y) = mapAbsolute(event.x, event.y)
                            listener?.onPointerMoveAbsolute(x, y, 0)
                        }
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2) {
                    val duration = System.currentTimeMillis() - secondDownTime
                    val travel = abs(event.x - downX) + abs(event.y - downY)
                    if (duration < TAP_TIMEOUT_MS && travel < TAP_SLOP_PX) {
                        listener?.onPointerButtons(MASK_RIGHT)
                        listener?.onPointerButtons(0)
                    }
                    val remaining = if (event.getActionIndex() == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                    scrollAccumX = 0f
                    scrollAccumY = 0f
                }
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                val duration = System.currentTimeMillis() - downTime
                val travel = abs(event.x - downX) + abs(event.y - downY)
                if (dragButton >= 0) {
                    listener?.onPointerButtons(0)
                    dragButton = -1
                } else if (!longPressFired && duration < TAP_TIMEOUT_MS && travel < TAP_SLOP_PX) {
                    click(event.x, event.y, MASK_LEFT)
                }
                // A long-press without movement in direct mode already clicked.
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragButton >= 0) {
                    listener?.onPointerButtons(0)
                    dragButton = -1
                }
            }
        }
        return true
    }

    private fun handleScroll(event: MotionEvent) {
        // Use the first two pointers' midpoint for scroll distance.
        val midY = (event.getY(0) + event.getY(1)) / 2f
        val midX = (event.getX(0) + event.getX(1)) / 2f
        val dy = midY - lastY
        val dx = midX - lastX
        lastX = midX
        lastY = midY
        scrollAccumY += dy
        scrollAccumX += dx
        while (abs(scrollAccumY) >= SCROLL_NOTCH_PX) {
            val notches = (scrollAccumY / SCROLL_NOTCH_PX).toInt()
            listener?.onWheel(notches, false)
            scrollAccumY -= notches * SCROLL_NOTCH_PX
        }
        while (abs(scrollAccumX) >= SCROLL_NOTCH_PX) {
            val notches = (scrollAccumX / SCROLL_NOTCH_PX).toInt()
            listener?.onWheel(notches, true)
            scrollAccumX -= notches * SCROLL_NOTCH_PX
        }
    }

    private fun sendRelativeMove(dx: Float, dy: Float) {
        val sx = (dx * TRACKPAD_SENSITIVITY).toInt()
        val sy = (dy * TRACKPAD_SENSITIVITY).toInt()
        if (sx == 0 && sy == 0) return
        listener?.onPointerMoveRelative(sx, sy, 0)
    }

    private fun sendRelativeDrag(dx: Float, dy: Float) {
        val sx = (dx * TRACKPAD_SENSITIVITY).toInt()
        val sy = (dy * TRACKPAD_SENSITIVITY).toInt()
        listener?.onPointerMoveRelative(sx, sy, dragButton)
    }

    private fun click(vx: Float, vy: Float, mask: Int) {
        if (trackpadMode) {
            listener?.onPointerButtons(mask)
            handler.postDelayed({ listener?.onPointerButtons(0) }, 50)
        } else {
            val (x, y) = mapAbsolute(vx, vy)
            listener?.onPointerMoveAbsolute(x, y, 0)
            listener?.onPointerMoveAbsolute(x, y, mask)
            listener?.onPointerMoveAbsolute(x, y, 0)
        }
    }

    /** JPEG-stripe fallback: composite one stripe into the back buffer. */
    fun drawJpegStripe(stripeY: Int, data: ByteArray) {
        val bmp = jpegBitmap ?: if (streamWidth > 0 && streamHeight > 0) {
            val created = Bitmap.createBitmap(streamWidth, streamHeight, Bitmap.Config.ARGB_8888)
            jpegBitmap = created
            jpegCanvas = Canvas(created)
            created
        } else {
            return
        }
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
        jpegCanvas?.drawBitmap(decoded, 0f, stripeY.toFloat(), null)
        decoded.recycle()
        postInvalidate()
    }

    fun clearJpegBuffer() {
        jpegBitmap?.recycle()
        jpegBitmap = null
        jpegCanvas = null
        postInvalidate()
    }
}
