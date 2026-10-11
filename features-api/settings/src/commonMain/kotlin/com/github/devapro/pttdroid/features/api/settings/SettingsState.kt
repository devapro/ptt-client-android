package com.github.devapro.pttdroid.features.api.settings

/**
 * Everything the Settings screen renders.
 *
 * [form] is the one live copy of the in-progress settings form: editing it can never touch any
 * other feature's state. Which screen is up is the Voyager `Navigator`'s stack, and the form
 * simply follows it — seeded non-null on entry, dropped on leave.
 */
data class SettingsState(
    /** Non-null exactly while the Settings screen is up. See [SettingsFormState]. */
    val form: SettingsFormState? = null,
    /**
     * Whether "draw over other apps" is granted, for the floating-button warning in Settings.
     *
     * Defaults to `true` deliberately: desktop and iOS have no such permission concept and never
     * update this field, so the default has to be the one that shows no warning there. On
     * Android `MainActivity.onResume` polls the real value and reports it through
     * `SettingsViewModel.onOverlayPermissionResult`.
     */
    val canDrawOverlay: Boolean = true,
)
