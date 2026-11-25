package com.sany.iptv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Lightweight iOS-inspired Liquid Glass material for the classic Android View UI.
 * Layered gradients and specular reflections provide depth without expensive live blur.
 */
object SanyLiquidGlass {
    fun background(context: Context, radiusDp: Int, light: Boolean, accent: Int, topRim: Boolean = true): Drawable =
        LiquidGlassDrawable(context, radiusDp, light, accent, topRim)

    fun elevate(view: View, context: Context, amountDp: Float = 5f) {
        val density = context.resources.displayMetrics.density
        view.elevation = amountDp * density
        view.translationZ = amountDp * 0.35f * density
        view.cameraDistance = 12000f * density
        if (view is android.view.ViewGroup) {
            view.clipChildren = false
            view.clipToPadding = false
        }
    }

    private class LiquidGlassDrawable(
        context: Context,
        radiusDp: Int,
        private val light: Boolean,
        private val accent: Int,
        private val topRim: Boolean
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val radius = radiusDp * density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.7f * density
        }
        private val shape = Path()
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
            if (rect.isEmpty) return
            val save = canvas.save()
            shape.reset()
            shape.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.clipPath(shape)

            fillPaint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                if (light) intArrayOf(
                    0xF8FFFFFF.toInt(), 0xEAF4F8FF.toInt(), 0xDDE6F0FC.toInt(), 0xF4FFFFFF.toInt()
                ) else intArrayOf(
                    0xE94B6384.toInt(), 0xD51B2B43.toInt(), 0xE20D192B.toInt(), 0xE52B405D.toInt()
                ), null, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, fillPaint)

            shinePaint.shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.top + rect.height() * 0.72f,
                if (light) intArrayOf(0x88FFFFFF.toInt(), 0x20FFFFFF, 0x00FFFFFF)
                else intArrayOf(0x70FFFFFF, 0x18DCEBFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, shinePaint)

            val ribbon = Path().apply {
                moveTo(rect.left, rect.top + radius * 0.45f)
                cubicTo(rect.left + rect.width() * 0.28f, rect.top,
                    rect.left + rect.width() * 0.52f, rect.top,
                    rect.left + rect.width() * 0.72f, rect.top + radius * 0.22f)
                lineTo(rect.left + rect.width() * 0.40f, rect.top + radius * 1.45f)
                cubicTo(rect.left + rect.width() * 0.22f, rect.top + radius * 0.78f,
                    rect.left + rect.width() * 0.10f, rect.top + radius * 0.78f,
                    rect.left, rect.top + radius * 1.05f)
                close()
            }
            shinePaint.shader = LinearGradient(
                rect.left, rect.top, rect.left + rect.width() * 0.72f, rect.top + radius,
                intArrayOf(0x38FFFFFF, 0x12FFFFFF, 0x00FFFFFF),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawPath(ribbon, shinePaint)

            val accentGlow = Color.argb(if (light) 14 else 22,
                Color.red(accent), Color.green(accent), Color.blue(accent))
            shinePaint.shader = LinearGradient(
                rect.left, rect.bottom - radius * 1.8f, rect.left, rect.bottom,
                if (light) intArrayOf(0x00FFFFFF, accentGlow, 0x30FFFFFF)
                else intArrayOf(0x00000000, accentGlow, 0x303A5B86),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, shinePaint)

            edgePaint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                if (light) intArrayOf(0xFFFFFFFF.toInt(), 0xA9BBD2E8.toInt(), 0xEFFFFFFF.toInt())
                else intArrayOf(0xE6DDF0FF.toInt(), 0x668BB6E8, 0xB8D4E9FF.toInt()),
                null, Shader.TileMode.CLAMP
            )
            if (topRim) {
                canvas.drawRoundRect(rect, radius, radius, edgePaint)
            } else {
                val sideAndBottom = Path().apply {
                    moveTo(rect.left, rect.top + radius)
                    lineTo(rect.left, rect.bottom - radius)
                    quadTo(rect.left, rect.bottom, rect.left + radius, rect.bottom)
                    lineTo(rect.right - radius, rect.bottom)
                    quadTo(rect.right, rect.bottom, rect.right, rect.bottom - radius)
                    lineTo(rect.right, rect.top + radius)
                }
                canvas.drawPath(sideAndBottom, edgePaint)
            }

            innerPaint.color = if (light) 0xAFFFFFFF.toInt() else 0x3FFFFFFF
            val inset = density * 1.6f
            val inner = RectF(rect).apply { inset(inset, inset) }
            canvas.drawRoundRect(inner, (radius - inset).coerceAtLeast(0f),
                (radius - inset).coerceAtLeast(0f), innerPaint)
            canvas.restoreToCount(save)
            fillPaint.shader = null
            shinePaint.shader = null
            edgePaint.shader = null
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            shinePaint.alpha = alpha
            edgePaint.alpha = alpha
            innerPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            shinePaint.colorFilter = colorFilter
            edgePaint.colorFilter = colorFilter
            innerPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
