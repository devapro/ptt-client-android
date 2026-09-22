package com.github.devapro.pttdroid.domain

import platform.Foundation.NSDate

/** iOS's [UtcClock], mirroring `JvmUtcClock` in `jvmCommonMain`. */
class IosUtcClock : UtcClock {
    override fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
}
