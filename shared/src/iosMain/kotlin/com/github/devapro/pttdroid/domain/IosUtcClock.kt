package com.github.devapro.pttdroid.domain

import platform.Foundation.NSDate
// Kotlin/Native exposes NSDate's `timeIntervalSince1970` as a separate declaration in
// `platform.Foundation`, not as a member of the `NSDate` class — the same "categories need their
// own imports" rule CLAUDE.md records for `serverTrust`/`setActive`. Without it the property
// reads as unresolved.
import platform.Foundation.timeIntervalSince1970

/** iOS's [UtcClock], mirroring `JvmUtcClock` in `jvmCommonMain`. */
class IosUtcClock : UtcClock {
    override fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
}
