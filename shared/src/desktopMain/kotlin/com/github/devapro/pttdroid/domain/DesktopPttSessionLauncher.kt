package com.github.devapro.pttdroid.domain

/**
 * Desktop has no foreground-service concept to start first (that is an Android background-
 * execution restriction; a desktop process just keeps running) — but something still has to call
 * [PttController.start]/[PttController.stop], and on Android that call lives inside
 * `PttForegroundService`, which does not exist here. This is that call, made directly: not a
 * no-op, just a launcher with nothing to launch.
 */
internal class DesktopPttSessionLauncher(private val controller: PttController) : PttSessionLauncher {
    override fun start() = controller.start()
    override fun stop() = controller.stop()
}
