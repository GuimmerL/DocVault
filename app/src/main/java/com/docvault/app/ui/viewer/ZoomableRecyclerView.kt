package com.docvault.app.ui.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

/** RecyclerView that supports playbook-style pinch zoom and two-dimensional panning. */
class ZoomableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var panning = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                panning = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldZoom = zoom
                val newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
                val factor = newZoom / oldZoom
                val focusX = detector.focusX
                val focusY = detector.focusY
                // Keep the point under the user's fingers stationary while scaling.
                offsetX = focusX - (focusX - offsetX) * factor
                offsetY = focusY - (focusY - offsetY) * factor
                zoom = newZoom
                applyTransform()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (zoom <= 1f) resetZoom()
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetZoom()
                return true
            }
        })

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                panning = zoom > 1f
                if (panning) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (zoom > 1f && event.pointerCount == 1) {
                    offsetX += event.x - lastX
                    offsetY += event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    applyTransform()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                panning = false
            }
        }

        return if (zoom > 1f || event.pointerCount > 1) true else super.onTouchEvent(event)
    }

    private fun applyTransform() {
        val maxX = width * (zoom - 1f) / 2f
        val maxY = height * (zoom - 1f) / 2f
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
        scaleX = zoom
        scaleY = zoom
        translationX = offsetX
        translationY = offsetY
        translationZ = if (zoom > 1f) 8f else 0f
    }

    private fun resetZoom() {
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        applyTransform()
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}
