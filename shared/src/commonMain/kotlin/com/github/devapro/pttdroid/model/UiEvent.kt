package com.github.devapro.pttdroid.model

import org.jetbrains.compose.resources.StringResource

/**
 * One-shot UI effects.
 *
 * The shared effect vocabulary of every feature `ViewModel` — renamed from the original
 * single-view-model event type when the MVI stack split into per-feature ones, because it was
 * never Main-specific: any of the three screens can emit one. Lives in `:shared` (not in a
 * feature) precisely because all three `:features:*` event channels carry it and `:shared`
 * cannot see a feature module.
 */
sealed interface UiEvent {
    /**
     * Carries a resource id rather than a formatted string: reducers have no `Context`, and
     * resolving in the Activity is what keeps them free of one.
     */
    data class ShowMessage(val messageRes: StringResource) : UiEvent

    data object RequestMicPermission : UiEvent
    data object RequestOverlayPermission : UiEvent
}
