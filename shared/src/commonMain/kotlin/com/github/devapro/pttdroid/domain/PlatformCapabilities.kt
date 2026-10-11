package com.github.devapro.pttdroid.domain

/**
 * Whether this platform can run [com.github.devapro.pttdroid.internalserver.InternalPttServer],
 * the optional on-device relay.
 *
 * `InternalPttServer` lives in `:shared`'s `jvmCommonMain` — it embeds a Ktor CIO server, which
 * has no iOS actual and never will (see the class KDoc) — so iOS cannot see the class at all,
 * let alone start it. The user's decision for Phase 7a: desktop keeps hosting the relay: iOS
 * hides the setting entirely rather than showing a toggle that can never do anything.
 *
 * [com.github.devapro.pttdroid.data.settings.AppSettings.hostServerEnabled] is unaffected by this
 * — the stored field is never dropped, only the row that edits it. That matters if a settings
 * blob is ever shared/restored across platforms (e.g. a future sync feature), and it keeps
 * `SettingsRepository`'s schema identical on every platform.
 */
expect val canHostRelay: Boolean

/**
 * Whether this platform has more than one place audio can come out of, and so whether offering
 * the speaker/earpiece choice means anything here.
 *
 * A phone has a loudspeaker and a handset receiver, and which one is in use is the difference
 * between a walkie-talkie on the table and a private call — see
 * [com.github.devapro.pttdroid.data.settings.AudioOutput]. A desktop has one output device,
 * chosen in the operating system, and `javax.sound.sampled` exposes no equivalent switch: the
 * choice is hidden there rather than shown as a control that cannot do anything.
 *
 * The volume slider is not gated on this — every platform can attenuate what it plays.
 */
expect val canRouteAudioOutput: Boolean

/**
 * Whether this platform can draw a floating push-to-talk button over other apps. Only Android
 * has an overlay window; desktop and iOS hide the setting rather than show a switch that does
 * nothing. [com.github.devapro.pttdroid.data.settings.AppSettings.floatingButtonEnabled] is
 * still stored and round-tripped, like `hostServerEnabled` under [canHostRelay].
 */
expect val canShowFloatingButton: Boolean

/**
 * Whether the platform has hardware keys the app can take as a push-to-talk button (Android's
 * volume keys and headset media button). Desktop and iOS hide the setting.
 */
expect val canUseHardwareButtons: Boolean
