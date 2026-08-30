package com.github.devapro.pttdroid.model

import androidx.annotation.StringRes

/** One-shot UI effects. */
sealed interface MainEvent {
    /**
     * Carries a resource id rather than a formatted string: reducers have no `Context`, and
     * resolving in the Activity is what keeps them free of one.
     */
    data class ShowMessage(@param:StringRes val messageRes: Int) : MainEvent

    data object RequestMicPermission : MainEvent
    data object RequestOverlayPermission : MainEvent
}
