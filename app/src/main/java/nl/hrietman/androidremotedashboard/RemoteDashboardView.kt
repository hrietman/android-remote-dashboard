package nl.hrietman.androidremotedashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.roundToInt

class RemoteDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    interface TouchSink {
        fun onRemoteTouch(kind: Int, x: Int, y: Int)
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clearPaint = Paint().apply { color = Color.BLACK }
    private var screenBitmap: Bitmap? = null
    private var screenCanvas: Canvas? = null
    private var remoteWidth = 0
    private var remoteHeight = 0
    var touchSink: TouchSink? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
    }

    fun configure(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == remoteWidth && height == remoteHeight && screenBitmap != null) return
        remoteWidth = width
        remoteHeight = height
        screenBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        screenCanvas = Canvas(screenBitmap!!)
        screenCanvas?.drawColor(Color.BLACK)
        drawCurrentFrame()
    }

    fun applyFrame(frame: AssembledFrame) {
        val canvas = screenCanvas ?: return
        if ((frame.flags and Protocol.FLAG_IS_FULL_FRAME) != 0) {
            canvas.drawColor(Color.BLACK)
        }
        for (rect in frame.rects) {
            val tile = decodeTile(frame.encoding, rect.data) ?: continue
            canvas.drawBitmap(tile, rect.x.toFloat(), rect.y.toFloat(), bitmapPaint)
            if (!tile.isRecycled) {
                tile.recycle()
            }
        }
        drawCurrentFrame()
    }

    private fun decodeTile(encoding: Int, data: ByteArray): Bitmap? {
        return when (encoding) {
            Protocol.Encoding.JPEG,
            Protocol.Encoding.PNG -> BitmapFactory.decodeByteArray(data, 0, data.size)
            else -> null
        }
    }

    private fun drawCurrentFrame() {
        val target = holder.lockCanvas() ?: return
        try {
            target.drawRect(0f, 0f, target.width.toFloat(), target.height.toFloat(), clearPaint)
            val frame = screenBitmap ?: return
            target.drawBitmap(frame, null, target.clipBounds, bitmapPaint)
        } finally {
            holder.unlockCanvasAndPost(target)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val width = remoteWidth
        val height = remoteHeight
        if (width <= 0 || height <= 0) return false

        val remoteX = ((event.x / this.width) * width).roundToInt().coerceIn(0, width)
        val remoteY = ((event.y / this.height) * height).roundToInt().coerceIn(0, height)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchSink?.onRemoteTouch(Protocol.TouchKind.DOWN, remoteX, remoteY)
            MotionEvent.ACTION_MOVE -> touchSink?.onRemoteTouch(Protocol.TouchKind.MOVE, remoteX, remoteY)
            MotionEvent.ACTION_UP -> {
                touchSink?.onRemoteTouch(Protocol.TouchKind.UP, remoteX, remoteY)
                touchSink?.onRemoteTouch(Protocol.TouchKind.TAP, remoteX, remoteY)
            }
            MotionEvent.ACTION_CANCEL -> touchSink?.onRemoteTouch(Protocol.TouchKind.UP, remoteX, remoteY)
        }
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        drawCurrentFrame()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        drawCurrentFrame()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
}
