package com.example.drawingapp


import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import java.util.LinkedList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DrawingView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    // ... existing properties ...
    private var mDrawPath: CustomPath? = null
    private var mCanvasBitmap: Bitmap? = null
    private var mDrawPaint: Paint? = null
    private var mCanvasPaint: Paint? = null
    private var mBrushSize: Float = 0.toFloat()
    private var color = Color.BLACK
    private var canvas: Canvas? = null
    private val mPaths = ArrayList<CustomPath>()
    private val mUndoPaths = ArrayList<CustomPath>()
    private var isFloodFillMode = false

    // Add new bitmap for boundary detection
    private var boundaryBitmap: Bitmap? = null
    private var boundaryCanvas: Canvas? = null

    init {
        setUpDrawing()
    }

    private fun setUpDrawing() {
        mDrawPaint = Paint()
        mDrawPath = CustomPath(color, mBrushSize)
        mDrawPaint?.color = color
        mDrawPaint?.style = Paint.Style.STROKE
        mDrawPaint?.strokeJoin = Paint.Join.ROUND
        mDrawPaint?.strokeCap = Paint.Cap.ROUND
        mCanvasPaint = Paint(Paint.DITHER_FLAG)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mCanvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        boundaryBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        canvas = Canvas(mCanvasBitmap!!)
        boundaryCanvas = Canvas(boundaryBitmap!!)
    }

    fun setFloodFillMode(enabled: Boolean) {
        isFloodFillMode = enabled
    }

    private fun updateBoundaryBitmap() {
        boundaryBitmap?.eraseColor(Color.TRANSPARENT)

        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = mBrushSize
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.BLACK
        }

        // Draw all paths to boundary bitmap
        for (path in mPaths) {
            boundaryCanvas?.drawPath(path, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchX = event.x.toInt()
        val touchY = event.y.toInt()

        if (isFloodFillMode) {
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    updateBoundaryBitmap()
                    GlobalScope.launch(Dispatchers.Default) {
                        floodFill(touchX, touchY, color)
                    }
                }
            }
        } else {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    mDrawPath?.color = color
                    mDrawPath?.brushThickness = mBrushSize
                    mDrawPath?.reset()
                    mDrawPath!!.moveTo(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    mDrawPath!!.lineTo(event.x, event.y)
                }
                MotionEvent.ACTION_UP -> {
                    mPaths.add(mDrawPath!!)
                    mDrawPath = CustomPath(color, mBrushSize)
                }
                else -> return false
            }
        }
        invalidate()
        return true
    }

    private suspend fun floodFill(startX: Int, startY: Int, newColor: Int) {
        mCanvasBitmap?.let { bitmap ->
            boundaryBitmap?.let { boundary ->
                val width = bitmap.width
                val height = bitmap.height

                if (startX < 0 || startX >= width || startY < 0 || startY >= height) {
                    return
                }

                // Check if starting point is on a boundary
                if (boundary.getPixel(startX, startY) != Color.TRANSPARENT) {
                    return
                }

                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                val boundaryPixels = IntArray(width * height)
                boundary.getPixels(boundaryPixels, 0, width, 0, 0, width, height)

                val queue = LinkedList<Point>()
                queue.add(Point(startX, startY))
                val visited = HashSet<Point>()

                while (queue.isNotEmpty()) {
                    val point = queue.remove()
                    val x = point.x
                    val y = point.y

                    if (x < 0 || x >= width || y < 0 || y >= height ||
                        point in visited ||
                        boundaryPixels[y * width + x] != Color.TRANSPARENT) {
                        continue
                    }

                    visited.add(point)
                    pixels[y * width + x] = newColor

                    // Add adjacent pixels
                    queue.add(Point(x + 1, y))
                    queue.add(Point(x - 1, y))
                    queue.add(Point(x, y + 1))
                    queue.add(Point(x, y - 1))
                }

                withContext(Dispatchers.Main) {
                    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                    invalidate()
                }
            }
        }
    }

    // ... rest of existing methods (onDraw, setColor, setSizeForBrush, etc.) ...
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        mCanvasBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, mCanvasPaint)
        }

        for (path in mPaths) {
            mDrawPaint?.strokeWidth = path.brushThickness
            mDrawPaint?.color = path.color
            canvas.drawPath(path, mDrawPaint!!)
        }

        if (!mDrawPath!!.isEmpty) {
            mDrawPaint?.strokeWidth = mDrawPath!!.brushThickness
            mDrawPaint?.color = mDrawPath!!.color
            canvas.drawPath(mDrawPath!!, mDrawPaint!!)
        }
    }

    fun onClickUndo() {
        if (mPaths.size > 0) {
            mUndoPaths.add(mPaths.removeAt(mPaths.size - 1))
            invalidate()
        }
    }

    fun onClickRedo() {
        if (mUndoPaths.size > 0) {
            mPaths.add(mUndoPaths.removeAt(mUndoPaths.size - 1))
            invalidate()
        }
    }

    fun setSizeForBrush(newSize: Float) {
        mBrushSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, newSize, resources.displayMetrics)
        mDrawPaint!!.strokeWidth = mBrushSize
    }

    fun setColor(newColor: String) {
        color = Color.parseColor(newColor)
        mDrawPaint!!.color = color
    }

    internal inner class CustomPath(var color: Int, var brushThickness: Float) : Path()
}