package com.github.devapro.pttdroid.model

import org.jetbrains.compose.resources.StringResource

/** One-shot UI effects. */
sealed interface MainEvent {
    /**
     * Carries a resource id rather than a formatted string: reducers have no `Context`, and
     * resolving in the Activity is what keeps them free of one.
     */
    data class ShowMessage(val messageRes: StringResource) : MainEvent

    data object RequestMicPermission : MainEvent
    data object RequestOverlayPermission : MainEvent
}
