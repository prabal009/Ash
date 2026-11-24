package com.jarvis.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Ash acoustic status orb: obsidian core when off, restrained cyan breathing halo in wake standby,
 * sonar rings while listening, orbital arc while processing, harmonic cyan-violet pulse while
 * speaking, and coral indicator on error. Respects reduced-motion system settings.
 */
class ArcReactorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { OFF, IDLE, LISTENING, WORKING, SPEAKING, ERROR }

    var mode: Mode = Mode.OFF
        set(value) {
            if (field != value) {
                field = value
                shaderKey = 0L
                invalidate()
            }
        }

    /** Disable for screenshots/tests so a single deterministic static frame is drawn. */
    var animate = true

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = RectF()
    private val startTime = System.currentTimeMillis()
    private var shaderKey = 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val motionAllowed = animate && runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
        val t = if (motionAllowed) (System.currentTimeMillis() - startTime) / 1000f else 0.4f
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        if (r <= 1f) return
        val core = r * 0.46f

        val primary = when (mode) {
            Mode.OFF -> Color.rgb(36, 48, 67)
            Mode.IDLE -> Color.rgb(56, 189, 248)
            Mode.LISTENING -> Color.rgb(56, 189, 248)
            Mode.WORKING -> Color.rgb(245, 158, 11)
            Mode.SPEAKING -> Color.rgb(129, 140, 248)
            Mode.ERROR -> Color.rgb(248, 113, 113)
        }
        val secondary = when (mode) {
            Mode.SPEAKING -> Color.rgb(56, 189, 248)
            Mode.WORKING -> Color.rgb(56, 189, 248)
            else -> primary
        }

        // Subtle ambient depth halo for active states.
        if (mode != Mode.OFF) {
            val haloAlpha = if (mode == Mode.IDLE) 44 else 68
            halo.shader = RadialGradient(
                cx, cy, r * 0.96f,
                intArrayOf(withAlpha(primary, haloAlpha), withAlpha(secondary, haloAlpha / 3), Color.TRANSPARENT),
                floatArrayOf(0.25f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r * 0.96f, halo)
        }

        // Subtle outer architectural guide ring.
        ring.strokeWidth = maxOf(1.2f, r * 0.016f)
        ring.color = when (mode) {
            Mode.OFF -> Color.argb(140, 45, 60, 82)
            else -> withAlpha(primary, 64)
        }
        canvas.drawCircle(cx, cy, core + r * 0.22f, ring)

        when (mode) {
            Mode.OFF -> {
                ring.strokeWidth = maxOf(1f, r * 0.014f)
                ring.color = Color.argb(60, 56, 189, 248)
                canvas.drawCircle(cx, cy, core + r * 0.38f, ring)
            }
            Mode.LISTENING -> {
                ring.strokeWidth = maxOf(1.5f, r * 0.022f)
                for (i in 0 until 2) {
                    val p = ((t * 0.72f + i * 0.5f) % 1f)
                    ring.color = withAlpha(primary, (150 * (1 - p)).toInt())
                    canvas.drawCircle(cx, cy, core + (r * 0.94f - core) * p, ring)
                }
            }
            Mode.SPEAKING -> {
                ring.strokeWidth = maxOf(1.6f, r * 0.024f)
                for (i in 0 until 2) {
                    val p = ((t * 0.55f + i * 0.5f) % 1f)
                    val c = if (i == 0) primary else secondary
                    ring.color = withAlpha(c, (135 * (1 - p)).toInt())
                    canvas.drawCircle(cx, cy, core + (r * 0.90f - core) * p, ring)
                }
            }
            Mode.WORKING -> {
                val ar = core + r * 0.22f
                ring.strokeWidth = maxOf(2f, r * 0.032f)
                arc.set(cx - ar, cy - ar, cx + ar, cy + ar)
                ring.color = primary
                canvas.drawArc(arc, t * 210f, 88f, false, ring)
                ring.color = withAlpha(secondary, 140)
                canvas.drawArc(arc, t * 210f + 180f, 54f, false, ring)
            }
            Mode.ERROR -> {
                ring.strokeWidth = maxOf(2f, r * 0.028f)
                ring.color = withAlpha(primary, 185)
                canvas.drawCircle(cx, cy, core + r * 0.22f, ring)
            }
            Mode.IDLE -> {}
        }

        val breathe = when (mode) {
            Mode.IDLE -> 1f + 0.032f * sin(t * 1.5f)
            Mode.SPEAKING -> 1f + 0.045f * sin(t * 4.2f)
            else -> 1f
        }
        val radiusNow = core * breathe
        val key = (width.toLong() shl 32) or (height.toLong() shl 16) or mode.ordinal.toLong()
        if (shaderKey != key) {
            shaderKey = key
            fill.shader = if (mode == Mode.OFF) {
                RadialGradient(
                    cx, cy - core * 0.2f, core * 1.15f,
                    intArrayOf(Color.rgb(29, 39, 54), Color.rgb(17, 22, 31)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            } else {
                RadialGradient(
                    cx, cy - core * 0.25f, core * 1.15f,
                    intArrayOf(Color.rgb(224, 242, 254), primary, withAlpha(secondary, 220)),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
        }
        canvas.drawCircle(cx, cy, radiusNow, fill)

        if (mode == Mode.OFF) {
            // Subtle dormant center pin.
            val prevShader = fill.shader
            fill.shader = null
            fill.color = Color.argb(110, 56, 189, 248)
            canvas.drawCircle(cx, cy, core * 0.18f, fill)
            fill.shader = prevShader
        }

        if (motionAllowed && mode != Mode.OFF && mode != Mode.ERROR && isAttachedToWindow) {
            postInvalidateOnAnimation()
        }
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
}
