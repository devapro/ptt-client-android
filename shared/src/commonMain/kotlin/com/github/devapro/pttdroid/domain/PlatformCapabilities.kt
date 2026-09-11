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
