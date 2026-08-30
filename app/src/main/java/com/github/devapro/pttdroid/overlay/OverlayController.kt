package com.github.devapro.pttdroid.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.getSystemService
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.domain.PttState
import com.github.devapro.pttdroid.ui.PttUiStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import timber.log.Timber

/**
 * Adds and removes the floating PTT bubble.
 *
 * Owned by the foreground service so the bubble survives the Activity going away — the point
 * of the feature is talking without opening the app.
 */
class OverlayController(
    private val context: Context,
    private val controller: PttController,
    private val settingsRepository: SettingsRepository,
    scope: CoroutineScope,
) {
    /**
     * WindowManager.addView/updateViewLayout/removeView must run on the main thread — they
     * build a ViewRootImpl Handler. The session scope is on Dispatchers.IO, so view work gets
     * its own main-thread scope.
     */
    private val uiScope: CoroutineScope = scope + SupervisorJob() + Dispatchers.Main.immediate

    private var bubble: OverlayBubbleView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var enabledBySetting = false
    private var appVisible = false
    private var lastState = PttState()

    private val windowManager: WindowManager?
        get() = context.getSystemService()

    /** True when the user has granted "draw over other apps". */
    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    /** Shows or hides the bubble to match [enabled], and repaints it for [state]. */
    fun sync(enabled: Boolean, state: PttState) {
        enabledBySetting = enabled
        lastState = state
        apply()
    }

    /**
     * Suppresses the bubble while the app itself is on screen.
     *
     * The bubble exists to reach PTT from *other* apps. Floating over our own screen it is pure
     * duplication, and being positioned wherever the user last dragged it, it lands on top of
     * the main button as often as not. Called from the Activity's start/stop.
     */
    fun setAppVisible(visible: Boolean) {
        if (appVisible == visible) return
        appVisible = visible
        apply()
    }

    private fun apply() {
        if (enabledBySetting && !appVisible && canDrawOverlay()) show() else hide()
        bubble?.render(PttUiStatus.of(lastState), lastState.channel)
    }

    private fun show() {
        if (bubble != null) return
        val manager = windowManager ?: return

        val view = OverlayBubbleView(
            context = context,
            onTalkStart = { controller.requestTalk() },
            onTalkStop = { controller.releaseTalk() },
            onDrag = { dx, dy -> moveBy(dx, dy) },
            onDragFinished = { persistPosition() },
        )

        val size = sizePx()
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        uiScope.launch {
            val settings = settingsRepository.settings.first()
            params.x = settings.floatingButtonX
            params.y = settings.floatingButtonY
            runCatching { manager.addView(view, params) }
                .onSuccess {
                    bubble = view
                    layoutParams = params
                    val current = controller.state.value
                    view.render(PttUiStatus.of(current), current.channel)
                    Timber.i("Floating PTT bubble shown at %d,%d", params.x, params.y)
                }
                .onFailure { Timber.e(it, "Could not add the overlay window") }
        }
    }

    fun hide() {
        val view = bubble ?: return
        bubble = null
        layoutParams = null
        runCatching { windowManager?.removeView(view) }
            .onFailure { Timber.d("Overlay removal failed: %s", it.toString()) }
    }

    private fun moveBy(dx: Int, dy: Int) {
        val view = bubble ?: return
        val params = layoutParams ?: return
        params.x += dx
        params.y += dy
        runCatching { windowManager?.updateViewLayout(view, params) }
            .onFailure { Timber.d("Overlay move failed: %s", it.toString()) }
    }

    private fun persistPosition() {
        val params = layoutParams ?: return
        uiScope.launch { settingsRepository.setFloatingButtonPosition(params.x, params.y) }
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // TYPE_APPLICATION_OVERLAY is the only overlay type allowed since Android 8.
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /**
     * The bubble was a flat `200` raw pixels, which is a different physical size on every phone —
     * a thumb-sized target on an mdpi tablet and a pinhead on a 4x screen. It carries a channel
     * number and a microphone now, so it is sized in dp and has to stay legible at 1x.
     */
    private fun sizePx(): Int =
        (SIZE_DP * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val SIZE_DP = 100f
    }
}
