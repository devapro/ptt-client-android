package com.github.devapro.pttdroid.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.github.devapro.pttdroid.ui.PttUiStatus
import kotlin.math.abs
import kotlin.math.min

/**
 * The floating PTT bubble: a record button you can hit without looking, plus the one piece of
 * context you cannot get anywhere else while another app is in front — which channel you are on.
 *
 * Deliberately a plain [View] rather than a `ComposeView`: hosting Compose in a `WindowManager`
 * window needs a hand-rolled LifecycleOwner, ViewModelStoreOwner and SavedStateRegistryOwner (or
 * a custom Recomposer), all of which can leak or crash. This window must never take the
 * foreground service down with it.
 *
 * Being a real View is also what allows genuine press-and-hold — the widget can only receive
 * discrete clicks.
 *
 * Everything is drawn from primitives rather than loaded as a drawable: this view is inflated
 * against a service `Context`, where a vector's `?attr/…` tint has no guaranteed theme to
 * resolve against, and a throw here takes the overlay window with it.
 *
 * It reads the same [PttUiStatus] as the app screen, so a colour means here what it means there.
 * The dark halo and light rim exist because this floats over arbitrary wallpapers and other
 * apps — a flat disc vanishes against anything of a similar tone.
 */
class OverlayBubbleView(
    context: Context,
    private val onTalkStart: () -> Unit,
    private val onTalkStop: () -> Unit,
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onDragFinished: () -> Unit,
) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 0, 0)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(56, 8, 17, 26)
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
    }
    private val inkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = INK
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val micBounds = RectF()
    private val micPath = Path()

    private var status: PttUiStatus = PttUiStatus.OFFLINE
    private var channel: Int = 1
    private var caption: String = context.getString(status.captionRes)
    private var pressed = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false

    /** Repaints for [next] and [onChannel]. No-ops when nothing visible has changed. */
    fun render(next: PttUiStatus, onChannel: Int) {
        if (status == next && channel == onChannel) return
        status = next
        channel = onChannel
        caption = context.getString(next.captionRes)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f
        val disc = outer * 0.88f

        fillPaint.color = status.argb.toInt()
        rimPaint.strokeWidth = outer * 0.045f

        canvas.drawCircle(cx, cy + outer * 0.03f, disc, haloPaint)
        canvas.drawCircle(cx, cy, disc, fillPaint)
        canvas.drawCircle(cx, cy, disc * 0.9f, rimPaint)
        if (pressed) canvas.drawCircle(cx, cy, disc, rimPaint)

        drawChannel(canvas, cx, cy, disc)
        drawMic(canvas, cx, cy + disc * 0.04f, disc * 0.62f)
        drawCaption(canvas, cx, cy, disc)
    }

    /** The number, up top. Zero-padded so 9 → 10 does not shift the glyph below it. */
    private fun drawChannel(canvas: Canvas, cx: Float, cy: Float, disc: Float) {
        textPaint.textSize = disc * 0.30f
        canvas.drawText(
            channel.toString().padStart(2, '0'),
            cx,
            cy - disc * 0.44f - (textPaint.ascent() + textPaint.descent()) / 2f,
            textPaint,
        )
    }

    private fun drawCaption(canvas: Canvas, cx: Float, cy: Float, disc: Float) {
        // The captions are not all the same length, and the bubble is small, so the text is
        // measured down to fit rather than clipped.
        textPaint.textSize = disc * 0.22f
        val width = textPaint.measureText(caption)
        val maxWidth = disc * 1.5f
        if (width > maxWidth) textPaint.textSize *= maxWidth / width
        canvas.drawText(
            caption,
            cx,
            cy + disc * 0.62f - (textPaint.ascent() + textPaint.descent()) / 2f,
            textPaint,
        )
    }

    /**
     * A microphone, in a box of side [side] centred on ([cx], [cy]) — capsule, cradle, stem and
     * foot. Struck through whenever a press could not start a transmission, so the bubble is not
     * relying on colour alone to say "not now".
     */
    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, side: Float) {
        micPath.reset()

        micBounds.set(cx - side * 0.17f, cy - side * 0.48f, cx + side * 0.17f, cy + side * 0.04f)
        micPath.addRoundRect(micBounds, side * 0.17f, side * 0.17f, Path.Direction.CW)

        micBounds.set(cx - side * 0.32f, cy - side * 0.30f, cx + side * 0.32f, cy + side * 0.34f)
        inkStrokePaint.strokeWidth = side * 0.09f
        canvas.drawArc(micBounds, 0f, 180f, false, inkStrokePaint)

        micBounds.set(cx - side * 0.05f, cy + side * 0.30f, cx + side * 0.05f, cy + side * 0.46f)
        micPath.addRect(micBounds, Path.Direction.CW)

        micBounds.set(cx - side * 0.22f, cy + side * 0.44f, cx + side * 0.22f, cy + side * 0.52f)
        micPath.addRoundRect(micBounds, side * 0.04f, side * 0.04f, Path.Direction.CW)

        canvas.drawPath(micPath, inkPaint)

        if (!status.isControlLive) {
            inkStrokePaint.strokeWidth = side * 0.11f
            canvas.drawLine(
                cx - side * 0.38f,
                cy - side * 0.44f,
                cx + side * 0.38f,
                cy + side * 0.44f,
                inkStrokePaint,
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                // True push-to-talk: the microphone opens on touch-down, not on release.
                pressed = true
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onTalkStart()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val movedFar = abs(event.rawX - downRawX) > touchSlop ||
                    abs(event.rawY - downRawY) > touchSlop
                if (!dragging && movedFar) {
                    dragging = true
                    // Started as a drag, so cancel any transmission it had begun.
                    releasePress()
                }
                if (dragging) {
                    onDrag((event.rawX - lastRawX).toInt(), (event.rawY - lastRawY).toInt())
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    onDragFinished()
                }
                releasePress()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                releasePress()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Idempotent, and called from every path that can end a press — including the window being
     * torn down mid-hold. Losing this leaves the floor held with the microphone open.
     */
    private fun releasePress() {
        if (!pressed) return
        pressed = false
        onTalkStop()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        releasePress()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Matches `OnSignal` in the app's PTT button — every status accent is a mid tone. */
        val INK = Color.rgb(8, 17, 26)
    }
}
