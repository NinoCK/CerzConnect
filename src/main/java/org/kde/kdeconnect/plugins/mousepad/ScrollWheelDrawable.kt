/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.mousepad

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * The middle button's scroll wheel: an outlined pill that grows up out of the button while it's held,
 * shaded toward the way it scrolls. Drawn in [host]'s overlay, so it never takes touches.
 */
class ScrollWheelDrawable(private val host: ViewGroup) : Drawable() {

    private val dp = host.resources.displayMetrics.density
    private val color = MaterialColors.getColor(host, androidx.appcompat.R.attr.colorPrimary)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        color = this@ScrollWheelDrawable.color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = RectF()
    private val path = Path()
    private var anchorX = 0f
    private var anchorY = 0f

    /** -1 (bottom) to 1 (top), eased toward [target]: the middle until it scrolls, then the end it scrolls to */
    private var shade = 0f
    private var target = 0f
    private var lastFrame = 0L

    private var progress = 0f
    private val animator = ValueAnimator().apply {
        duration = 220
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidateSelf()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (progress == 0f) host.overlay.remove(this@ScrollWheelDrawable)
            }
        })
    }

    fun show(button: View) {
        val hostAt = IntArray(2).also(host::getLocationInWindow)
        val buttonAt = IntArray(2).also(button::getLocationInWindow)
        anchorX = buttonAt[0] - hostAt[0] + button.width / 2f
        anchorY = buttonAt[1] - hostAt[1] + button.height / 2f
        setBounds(0, 0, host.width, host.height)
        shade = 0f
        target = 0f
        animator.cancel()
        host.overlay.add(this)
        animate(1f)
    }

    fun hide() = animate(0f)

    fun scrolled(up: Boolean) {
        target = if (up) 1f else -1f
        invalidateSelf()
    }

    private fun animate(to: Float) {
        animator.cancel()
        animator.setFloatValues(progress, to)
        animator.start()
    }

    override fun draw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceAtMost(50).toFloat()
        lastFrame = now
        shade += (target - shade) * (1 - exp(-dt / 80f))

        // From a circle around the icon to a tall pill whose bottom end still wraps it
        val half = lerp(12 * dp, 22 * dp, progress)
        val top = lerp(anchorY - 12 * dp, max(8 * dp, anchorY + 22 * dp - 200 * dp), progress)
        pill.set(anchorX - half, top, anchorX + half, anchorY + half)
        path.reset()
        path.addRoundRect(pill, half, half, Path.Direction.CW)

        val y = pill.centerY() - shade * (pill.height() / 2 - half)
        val glow = ColorUtils.setAlphaComponent(color, ((0.2f + 0.5f * abs(shade)) * progress * 255).toInt())
        fill.shader = LinearGradient(0f, y - 3 * half, 0f, y + 3 * half, intArrayOf(Color.TRANSPARENT, glow, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(pill, fill)
        canvas.restore()

        stroke.alpha = (progress * 255).toInt()
        canvas.drawPath(path, stroke)

        if (abs(target - shade) > 0.01f) invalidateSelf()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
