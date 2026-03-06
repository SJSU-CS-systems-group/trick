package org.trcky.trick.data

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS implementation of currentTimeMillis.
 */
actual fun currentTimeMillis(): Long {
    val date = NSDate()
    return (date.timeIntervalSince1970 * 1000.0).toLong()
}
