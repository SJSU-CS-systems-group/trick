package net.discdd.trick.data

import platform.Foundation.NSDate

/**
 * iOS implementation of currentTimeMillis for ContactRepository.
 */
internal actual fun currentTimeMillis(): Long {
    return (NSDate().timeIntervalSince1970 * 1000).toLong()
}

