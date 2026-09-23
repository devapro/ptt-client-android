package com.github.devapro.pttdroid.features.settings

import com.github.devapro.pttdroid.features.api.settings.SettingsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Settings screen's exit rule, as a plain object so the rule has tests.
 *
 * This is the only route out of Settings for both the back arrow and a successful save, so it is
 * the one piece of net-new Voyager-era logic whose loss would strand the user in the screen — the
 * shape known-issues #22 takes when a one-shot path is wired to something that quietly stops
 * firing. It is deliberately free of Compose and of Voyager: [observe] is what the screen root
 * collects, [onFormPresent] is the transition it feeds that collector, [onClose] is the arrow's
 * dispatch, and `pop` is whatever the root's `navigator::pop` turns out to be.
 *
 * **Leaving is derived, not (only) dispatched.** `SettingsState.form` going seeded → null is the
 * save's exit signal: `SaveSettingsReducer` decides *asynchronously*, and only it knows whether
 * the write was accepted (known-issues #25's `hasError` guard refuses a broken form, in which
 * case the form stays seeded and the user stays in Settings with their edits intact). So a
 * `form == null` **level** would bounce out before `OpenSettingsReducer` has read the repository
 * — hence the `wasSeeded` flag: only a seeded → null **transition** pops.
 *
 * **The arrow also pops on its own action** ([onClose]). That is belt-and-braces rather than a
 * fix: the arrow dispatches `SettingsAction.Close`, whose reducer clears the form, and the edge
 * would have popped a moment later anyway. What the extra path buys is independence from a
 * reducer that could stop clearing `form` without the arrow noticing. [popped] is what makes the
 * two paths one pop: whichever fires first wins and the other is a no-op, so a `Close` can never
 * double-pop the stack (popping Main behind Settings would be a far worse bug than the coupling
 * this removes).
 */
internal class SettingsExitObserver(
    private val pop: () -> Unit,
) {
    /** Null until the first observation: the frames before the seed lands must not pop. */
    private var wasSeeded: Boolean? = null

    /** One exit per observer, however many paths reach it. */
    private var popped = false

    /**
     * Watch [state] for the seeded → null transition. Collecting the state flow rather than
     * reading a `MutableStateFlow.value` inside a `snapshotFlow` keeps this the same value the
     * composition already renders, and — the reason it lives here instead of inline in the root —
     * makes the real path collectable by a test with no composition and no frame clock around it.
     */
    suspend fun observe(state: Flow<SettingsState>) {
        state
            .map { it.form != null }
            .distinctUntilChanged()
            .collect { onFormPresent(it) }
    }

    /** A form appearing records the seed; a form *disappearing* after a seed is the pop. */
    fun onFormPresent(present: Boolean) {
        if (wasSeeded == true && !present) popOnce()
        wasSeeded = present
    }

    /** The arrow's `SettingsAction.Close`, handled beside the edge — see the class KDoc. */
    fun onClose() = popOnce()

    private fun popOnce() {
        if (popped) return
        popped = true
        pop()
    }
}
