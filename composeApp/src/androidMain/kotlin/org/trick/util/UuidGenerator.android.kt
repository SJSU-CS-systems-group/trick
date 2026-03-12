package org.trick.util

import java.util.UUID

actual fun generateUuid(): String = UUID.randomUUID().toString()
