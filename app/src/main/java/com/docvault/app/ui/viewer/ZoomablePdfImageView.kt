package com.docvault.app.ui.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/** A per-page zoom surface: RecyclerView scrolls normally at 1x; this view owns gestures when zoomed. */
class ZoomablePdfImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var zooming = false
    private val panSensitivity = 1.6f
    private val pageGapDp = 16

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun updatePageSpacing() {
        var current: android.view.View = this
        var ancestor = parent
        while (ancestor != null && ancestor !is androidx.recyclerview.widget.RecyclerView) {
            current = ancestor as? android.view.View ?: break
            ancestor = ancestor.parent
        }
        if (ancestor is androidx.recyclerview.widget.RecyclerView) {
            val params = current.layoutParams as? androidx.recyclerview.widget.RecyclerView.LayoutParams
            if (params != null && current.height > 0) {
                params.bottomMargin = if (zoom > 1f) {
                    dp(pageGapDp) + ((zoom - 1f) * current.height).toInt()
                } else {
                    dp(6)
                }
                current.layoutParams = params
            }
        }
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                zooming = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldZoom = zoom
                val newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
                val factor = newZoom / oldZoom
                // Keep the pixel beneath the pinch focus stationary.
                offsetX = detector.focusX - (detector.focusX - offsetX) * factor
                offsetY = detector.focusY - (detector.focusY - offsetY) * factor
                zoom = newZoom
                applyTransform()
                updatePageSpacing()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (zoom <= 1.01f) resetZoom()
            }
        })

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetZoom()
                return true
            }
        })

    init {
        isClickable = true
        scaleType = ScaleType.FIT_CENTER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                zooming = zoom > 1f
                if (zooming) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (zoom > 1f && event.pointerCount == 1) {
                    offsetX += (event.x - lastX) * panSensitivity
                    offsetY += (event.y - lastY) * panSensitivity
                    lastX = event.x
                    lastY = event.y
                    applyTransform()
                    return true
                }
                if (zoom <= 1f && event.pointerCount == 1) {
                    // Give the vertical RecyclerView back its normal scroll handling.
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                zooming = false
            }
        }

        return if (zoom > 1f || event.pointerCount > 1 || zooming) true else super.onTouchEvent(event)
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
        updatePageSpacing()
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}
