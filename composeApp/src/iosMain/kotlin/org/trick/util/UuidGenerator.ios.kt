package org.trick.util

import platform.Foundation.NSUUID

actual fun generateUuid(): String = NSUUID().UUIDString()
